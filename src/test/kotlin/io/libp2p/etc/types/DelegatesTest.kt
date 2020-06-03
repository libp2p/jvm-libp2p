package io.libp2p.etc.types

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class DelegatesTest {

    var i: Int by cappedVar(10, 5, 20)
    @Test
    fun cappedVarTest() {
        Assertions.assertEquals(10, i)
        i--
        Assertions.assertEquals(9, i)
        i -= 100
        Assertions.assertEquals(5, i)
        i = 19
        Assertions.assertEquals(19, i)
        i++
        Assertions.assertEquals(20, i)
        i++
        Assertions.assertEquals(20, i)
    }
}
