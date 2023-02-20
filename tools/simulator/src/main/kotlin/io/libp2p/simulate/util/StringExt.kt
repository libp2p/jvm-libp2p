package io.libp2p.simulate.util

import kotlin.math.max

fun String.align(width: Int, alignLeft: Boolean = true, fillChar: Char = ' '): String {
    val n = max(1, width - length)
    return if (alignLeft) this + fillChar.toString().repeat(n) else fillChar.toString().repeat(n) + this
}

fun String.formatTable(firstLineHeaders: Boolean = true, separator: String = "\t", alignLeft: Boolean = true): String {
    val list = this.split("\n").map { it.split(separator) }
    require(list.map { it.size }.minOrNull() == list.map { it.size }.maxOrNull()) { "Different number of columns" }
    val colSizes = list[0].indices.map { col -> list.map { it[col].length + 1 }.maxOrNull() }
    val strings = list.map { raw ->
        raw.indices.map { raw[it].align(colSizes[it]!!, alignLeft) }
            .joinToString("")
    }.toMutableList()

    if (firstLineHeaders) {
        strings.add(1, colSizes.map { "-".repeat(it!! - 1) + " " }.joinToString(""))
    }
    return strings.joinToString("\n")
}
