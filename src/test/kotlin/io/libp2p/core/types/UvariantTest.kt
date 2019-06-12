package io.libp2p.core.types

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Created by Anton Nashatyrev on 11.06.2019.
 */
class UvariantTest {

    @Test
    fun test1() {
        for (i in 0 .. 10000L) {
            val buf = Unpooled.buffer()
            buf.writeUvarint(i);
            Assertions.assertEquals(i, buf.readUvarint())
            Assertions.assertFalse(buf.isReadable)
        }
    }

}