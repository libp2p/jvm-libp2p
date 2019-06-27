package io.libp2p.core.security.secio

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.netty.buffer.ByteBuf
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * Created by Anton Nashatyrev on 18.06.2019.
 */

class Test {
    @Test
    fun test1() {
        val (privKey1, pubKey1) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, pubKey2) = generateKeyPair(KEY_TYPE.ECDSA)
        val ch1 = Channel<ByteBuf>(8)
        val ch2 = Channel<ByteBuf>(8)
        var bb1: ByteBuf? = null
        var bb2: ByteBuf? = null

        val hs1 = SecioHandshake({ bb -> bb1 = bb }, privKey1, null)
        val hs2 = SecioHandshake({ bb -> bb2 = bb }, privKey2, null)

        hs1.start()
        hs2.start()

        Assertions.assertTrue(bb1 != null)
        Assertions.assertTrue(bb2 != null)
        var msgFor1 = bb2!!
        var msgFor2 = bb1!!
        bb1 = null
        bb2 = null

        hs1.onNewMessage(msgFor1)
        hs2.onNewMessage(msgFor2)
        Assertions.assertTrue(bb1 != null)
        Assertions.assertTrue(bb2 != null)

        val keys1 = hs1.onNewMessage(bb2!!)
        val keys2 = hs2.onNewMessage(bb1!!)

        Assertions.assertTrue(keys1 != null)
        Assertions.assertTrue(keys2 != null)

        val enc1 = keys1!!.first.cipher.doFinal("Hello".toByteArray())
        val dec1 = keys2!!.second.cipher.doFinal(enc1)

        Assertions.assertEquals("Hello", dec1.toString(StandardCharsets.UTF_8))

        val enc2 = keys2!!.first.cipher.doFinal("Hello".toByteArray())
        val dec2 = keys1!!.second.cipher.doFinal(enc2)

        Assertions.assertEquals("Hello", dec2.toString(StandardCharsets.UTF_8))
    }
}