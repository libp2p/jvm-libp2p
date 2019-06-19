package io.libp2p.core.security.secio

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.netty.buffer.ByteBuf
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * Created by Anton Nashatyrev on 18.06.2019.
 */

class Test {
    @Test
    fun test1() {
        runBlocking {
            val (privKey1, pubKey1) = generateKeyPair(KEY_TYPE.ECDSA)
            val (privKey2, pubKey2) = generateKeyPair(KEY_TYPE.ECDSA)
            val ch1 = Channel<ByteBuf>(8)
            val ch2 = Channel<ByteBuf>(8)
            val hs1 = SecioHandshake(ch1, { bb -> ch2.send(bb) }, privKey1, null)
            val hs2 = SecioHandshake(ch2, { bb -> ch1.send(bb) }, privKey2, null)
            val dres1 = async { hs1.doHandshake() }
            val dres2 = async { hs2.doHandshake() }

            val res1 = dres1.await()
            val res2 = dres2.await()

            val enc1 = res1.first.cipher.doFinal("Hello".toByteArray())
            val dec1 = res2.second.cipher.doFinal(enc1)

            Assertions.assertEquals("Hello", dec1.toString(StandardCharsets.UTF_8))

            val enc2 = res2.first.cipher.doFinal("Hello".toByteArray())
            val dec2 = res1.second.cipher.doFinal(enc2)

            Assertions.assertEquals("Hello", dec2.toString(StandardCharsets.UTF_8))
        }
    }
}