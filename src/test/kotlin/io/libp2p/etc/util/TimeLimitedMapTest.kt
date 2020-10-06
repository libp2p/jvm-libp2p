package io.libp2p.etc.util

import io.libp2p.etc.types.seconds
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

class TimeLimitedMapTest {

    @Test
    fun simpleTest() {
        val time = AtomicLong()
        val map = TimeLimitedMap<String, String>(mutableMapOf(), { time.get() }, 1.seconds)
        map["a1"] = "v"
        map["a2"] = "v"
        time.set(500)
        Assertions.assertEquals(map.size, 2)
        map["a3"] = "v"
        Assertions.assertEquals(map.size, 3)
        time.set(900)
        Assertions.assertEquals(map.size, 3)
        map["a1"] = "v"
        Assertions.assertEquals(map.size, 3)
        time.set(1001)
        map["a1"] = "v"
        Assertions.assertEquals(map.size, 1)
        Assertions.assertEquals(map["a3"], "v")

        time.set(1501)
        map["a3"] = "v"
        Assertions.assertEquals(map.size, 0)
    }
}