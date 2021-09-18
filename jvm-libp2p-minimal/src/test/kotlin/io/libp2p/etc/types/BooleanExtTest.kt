package io.libp2p.etc.types

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class BooleanExtTest {
    @Test
    fun testWhenTrue() {
        val shouldStayTrue = AtomicBoolean(true)
        val shouldBeChanged = AtomicBoolean(true)
        false.whenTrue { shouldStayTrue.set(false) }
        true.whenTrue { shouldBeChanged.set(false) }
        assert(shouldStayTrue.get())
        assertFalse(shouldBeChanged.get())
    }
}
