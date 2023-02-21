package io.libp2p.simulate.util

import org.junit.jupiter.api.Assertions
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

        Assertions.assertEquals(2, t2["a"]!!.size)
        Assertions.assertEquals(11, t2["a"]!![0])
        Assertions.assertEquals(21, t2["a"]!![1])

        val t3 = t2.transpose()
        Assertions.assertEquals(t1, t3)
    }
}
