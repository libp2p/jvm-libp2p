package io.libp2p.simulate.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CollectionExtTest {
    @Test
    fun transposeTest() {
        val t1 = listOf(
            mapOf(
                "a" to 11,
                "b" to 12,
                "c" to 13
            ),
            mapOf(
                "a" to 21,
                "b" to 22,
                "c" to 23
            )
        )

        val t2 = t1.transpose()

        assertEquals(2, t2["a"]!!.size)
        assertEquals(11, t2["a"]!![0])
        assertEquals(21, t2["a"]!![1])

        val t3 = t2.transpose()
        assertEquals(t1, t3)
    }
}
