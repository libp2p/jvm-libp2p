package io.libp2p.simulate.util

fun <TRowHeader, TColumnHeader : Comparable<TColumnHeader>, TValue> tableFillLast(defaultValue: TValue) =
    { _: TRowHeader, _: TColumnHeader, lastVal: TValue? -> lastVal ?: defaultValue }

fun <TRowHeader, TColumnHeader : Comparable<TColumnHeader>, TValue> tableFillDefault(defaultValue: TValue) =
    { _: TRowHeader, _: TColumnHeader, _: TValue? -> defaultValue }

fun <TRowHeader, TColumnHeader : Comparable<TColumnHeader>, TValue>
        Map<TRowHeader, Map<TColumnHeader, TValue>>.toTable(
    fillEmpty: (row: TRowHeader, col: TColumnHeader, lastVal: TValue?) -> TValue,
    sortColumns: Boolean = true
): Table<TValue> {

    val columnHeaders = if (sortColumns) {
        values.flatMap { it.keys }.distinct().sorted()
    } else {
        val headers = values.first().keys
        if (values.any { it.keys != headers }) {
            throw IllegalArgumentException("Different columns in rows: $this")
        }
        headers
    }

    val map = this.mapValues { rowEntry ->
        if (rowEntry.value.size == columnHeaders.size) {
            rowEntry.value
        } else {
            val tmp = mutableListOf<Pair<TColumnHeader, TValue>>()
            var lastVal: TValue? = null
            for (colHead in columnHeaders) {
                val v = rowEntry.value[colHead] ?: fillEmpty(rowEntry.key, colHead, lastVal)
                tmp += colHead to v
                lastVal = v
            }
            tmp.toMap()
        }
    }
    return Table(map)
}

fun <TValue> Collection<Map<*, TValue>>.toTable() = Table.fromRows(this)

fun Map<*, Collection<*>>.toStringTransposed(
    colSeparator: String = "\t",
    printHeaders: Boolean = true
): String {
    val ret = StringBuilder()
    if (printHeaders) {
        ret.append(keys.joinToString(colSeparator)).append("\n")
    }
    val iterators = values.map { it.iterator() }
    while (iterators.any { it.hasNext() }) {
        val rowString =
            iterators.map { if (it.hasNext()) it.next() else "" }.joinToString(colSeparator)
        ret.append(rowString).append("\n")
    }
    return ret.toString()
}

enum class Align { LEFT, RIGHT }

// rowMap < columnMap <value> >
class Table<TValue>(val data: Map<*, Map<*, TValue>>) {

    val rowCount get() = data.size
    val columnCount get() = data.entries.first().value.size

    val rowNames get() = data.keys.toList()
    val columnNames get() = data.entries.first().value.keys.toList()

    val columnNameToIndex by lazy {
        columnNames.mapIndexed { index, any -> any to index }.toMap()
    }

    init {
        require(rowCount > 0)
        require(data.filterNot { columnNames == it.value.keys.toList() }.isEmpty()) {
            "Order or number of columns in different rows differ $data"
        }
    }

    fun transposed(): Table<TValue> {
        data class RowColumnValue(val rowHead: Any?, val colHead: Any?, val value: TValue)

        val groupingBy = data
            .flatMap { (rowHead, row) ->
                row.map { (colHead, value) -> RowColumnValue(rowHead, colHead, value) }
            }
            .groupingBy { it.colHead }
            .aggregate { _, accum: MutableMap<Any?, TValue>?, elem, _ ->
                (accum ?: mutableMapOf()).also {
                    it[elem.rowHead] = elem.value
                }
            }
        return Table(groupingBy)
    }

    fun insertColumn(columnIndex: Int, columnName: String, values: Collection<TValue>): Table<TValue> {
        require(columnIndex <= columnCount)
        val newData = data.entries
            .zip(values)
            .associate { (raw, newEntry) ->
                val rawName = raw.key
                val rawEntries = raw.value.toList()
                val newEntries = rawEntries.take(columnIndex) +
                        (columnName as Any? to newEntry) +
                        rawEntries.drop(columnIndex)
                rawName to newEntries.toMap()
            }
        return Table(newData)
    }

    fun appendColumn(columnName: String, values: Collection<TValue>): Table<TValue> {
        require(values.size == rowCount)
        return insertColumn(columnCount, columnName, values)
    }
    fun appendColumns(columns: Map<*, Collection<TValue>>) =
        columns.entries.fold(this) { acc, newCol ->
            acc.appendColumn(newCol.key.toString(), newCol.value)
        }
    fun appendColumns(other: Table<TValue>) =
        appendColumns(other.transposed().data.mapValues { it.value.values })

    fun appendRows(other: Table<TValue>): Table<TValue> {
        require(columnNames == other.columnNames)
        return fromRows(data.values + other.data.values)
    }

    fun removeColumn(columnName: String): Table<TValue> =
        removeColumn(columnNameToIndex[columnName] ?: throw IllegalArgumentException("No such column: $columnName"))

    fun removeColumn(columnIndex: Int): Table<TValue> {
        require(columnIndex < columnCount)
        val newData = data.entries.associate { (rowName, rowData) ->
            val rowEntries = rowData.toList()
            rowName to (rowEntries.take(columnIndex) + rowEntries.drop(columnIndex + 1)).toMap()
        }
        return Table(newData)
    }

    fun getRowValues(rowIndex: Int): List<TValue> =
        data.values.drop(rowIndex).first().values.toList()

    fun getColumnValues(columnIndex: Int): List<TValue> {
        return transposed().getRowValues(columnIndex)
    }

    fun sorted(block: Sorter.() -> Unit): Table<TValue> {
        val sorter = Sorter()
        block(sorter)
        return sorter.apply()
    }

    fun filter(filter: (Map<*, TValue>) -> Boolean): Table<TValue> {
        return Table(data.filter { filter(it.value) })
    }

    fun print(delimiter: String = "\t", printRowHeader: Boolean = true): String {
        val s = StringBuilder()
        fun maybeRowHeader(s: String): String =
            if (printRowHeader) s else ""
        s.append(maybeRowHeader(" $delimiter") +
                data.values.first().keys.joinToString(separator = delimiter) + "\n")
        s.append(data
            .map { (rowHead, row) ->
                maybeRowHeader("" + rowHead + delimiter) + row.values.joinToString(separator = delimiter)
            }
            .joinToString(separator = "\n"))
        return s.toString()
    }

    fun printPretty(
        colHeaderDelimiter: String? = "=",
        rowHeaderDelimiter: String = " | ",
        valueFormatString: String = "%s",
        rowHeaderAlign: Align = Align.LEFT,
        columnAlign: Align = Align.RIGHT,
        printRowHeader: Boolean = true,
        printColHeader: Boolean = true,
        colHeaderDelimiterColDelim: String = "|"
    ): String {
        val rawHeaderWidth = rowNames.maxOf { it.toString().length }
        val colWidths = transposed().data.map { (colHeader, colValues) ->
            (colValues.values.map { String.format(valueFormatString, it) } + colHeader.toString())
                .maxOf { it.length }
        }

        fun String.padRowHeader() =
            if (rowHeaderAlign == Align.RIGHT) this.padStart(rawHeaderWidth) else this.padEnd(
                rawHeaderWidth
            )

        fun String.padCol(colIdx: Int) =
            if (columnAlign == Align.RIGHT) this.padStart(colWidths[colIdx]) else this.padEnd(
                colWidths[colIdx]
            )

        fun optionalRawHeader(s: String): String = if (printRowHeader) s else ""
        fun optionalRawHeader(i: Int): Int = if (printRowHeader) i else 0

        var s = ""
        if (printColHeader) {
            s += optionalRawHeader("".padRowHeader() + rowHeaderDelimiter) +
                    columnNames.indices
                        .joinToString(" ") { i -> columnNames[i].toString().padCol(i) } + "\n"

            if (colHeaderDelimiter != null) {
                s += optionalRawHeader(colHeaderDelimiter.repeat(rawHeaderWidth + rowHeaderDelimiter.length))
                s += colWidths.joinToString(colHeaderDelimiterColDelim) { colHeaderDelimiter.repeat(it) }
                s += "\n"
            }
        }
        s += data
            .map { (rowHader, row) ->
                val rowVals = row.values.toList()
                optionalRawHeader(rowHader.toString().padRowHeader() + rowHeaderDelimiter) +
                        rowVals.indices
                            .joinToString(" ") { valueFormatString.format(rowVals[it]).padCol(it) }

            }
            .joinToString("\n")
        return s;
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Table<*>
        return data == other.data
    }

    override fun hashCode(): Int {
        return data.hashCode()
    }

    companion object {

        fun  <TValue> fromRow(row: Map<*, TValue>) = fromRows(listOf(row))
        fun  <TValue> fromRows(rows: Collection<Map<*, TValue>>) =
                Table(rows.withIndex().associate { it.index to it.value })

        fun  <TValue> fromColumns(columns: Map<*, List<TValue>>): Table<TValue> {
            require(columns.isNotEmpty())
            require(columns.values.map { it.size }.toSet().size == 1) { "Number of elements in columns differ" }

            val rowsCount = columns.values.first().size

            val rows = (0 until rowsCount).map { rowIndex ->
                columns.mapValues { (_, columnData) ->
                    columnData[rowIndex]
                }
            }
            return fromRows(rows)
        }

        @Suppress("UNCHECKED_CAST")
        internal fun <T> optimisticAnyComparator(): Comparator<T> =
            Comparator { o1, o2 ->
                when {
                    o1 == null && o2 == null -> 0
                    o1 == null && o2 != null -> -1
                    o1 != null && o2 == null -> 1
                    o1 !is Comparable<*> || o2 !is Comparable<*> -> 0
                    else -> {
                        val c1 = o1 as Comparable<Comparable<*>>
                        c1.compareTo(o2)
                    }
                }
            }
    }

    internal data class ColumnSort<TValue>(
        val columnIndex: Int,
        val comparator: Comparator<TValue>
    )

    inner class Sorter {
        private val columnsSort = mutableListOf<ColumnSort<TValue>>()

        fun sortAscending(columnIndex: Int): Sorter = sort(columnIndex, false)
        fun sortDescending(columnIndex: Int): Sorter = sort(columnIndex, true)
        fun sortAscending(columnName: String): Sorter =
            sortAscending(columnNameToIndex[columnName] ?: throw IllegalArgumentException("No such column: $columnName"))
        fun sortDescending(columnName: String): Sorter =
            sortDescending(columnNameToIndex[columnName] ?: throw IllegalArgumentException("No such column: $columnName"))

        fun sort(
            columnIndex: Int,
            reversed: Boolean = false,
            comparator: Comparator<TValue> = optimisticAnyComparator()
        ): Sorter = apply {
            require(columnIndex < columnCount)
            columnsSort += ColumnSort(columnIndex, if (reversed) comparator.reversed() else comparator)
        }

        internal fun apply(): Table<TValue> {
            if (columnsSort.isEmpty()) return this@Table

            val columnIndices = columnsSort.map { it.columnIndex }
            val sortData = data.values.map {
                val rowValues = it.values.toList()
                columnIndices.map { rowValues[it] }
            }

//            columnsSort[0].columnIndex
//            val comparator0: Comparator<List<TValue>> =
//                Comparator.comparing({ it[columnsSort[0].columnIndex] }, columnsSort[0].comparator)
//            val comparator1: Comparator<List<TValue>> =
//                Comparator.comparing({ it[columnsSort[1].columnIndex] }, columnsSort[1].comparator)
//
//            val then = comparator0.then(comparator1)

            val rawComparator = columnsSort
                .withIndex()
                .map { (columnIndex, sorter) ->
                    val comparator0: Comparator<List<TValue>> = Comparator.comparing({ it[columnIndex] }, sorter.comparator)
                    comparator0
                }
                .reduce { acc, o -> acc.then(o) }


            val sortedIndexes = sortData
                .withIndex()
                .sortedWith(Comparator.comparing({ it.value }, rawComparator))
                .map { it.index }

//            val columnValuesToSort = columnsSort.map { getColumnValues(it.columnIndex) }
//            val sortedIndexes = columnValuesToSort
//                .withIndex()
//                .sortedWith(Comparator.comparing({ it.value }, comparator))
//                .map { it.index }
            val dataList = data.toList()

            val sortedDataMap = sortedIndexes.associate { dataList[it] }
            return Table(sortedDataMap)
        }
    }


}
