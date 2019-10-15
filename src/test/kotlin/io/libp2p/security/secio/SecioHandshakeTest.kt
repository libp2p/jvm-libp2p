package io.libp2p.security.secio

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.crypto.unmarshalPrivateKey
import io.libp2p.crypto.keys.secp256k1PublicKeyFromCoordinates
import io.libp2p.etc.types.fromHex
import io.netty.buffer.ByteBuf
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * Created by Anton Nashatyrev on 18.06.2019.
 */

class SecioHandshakeTest {
    @Test
    fun test1() {
        val (privKey1, pubKey1) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, pubKey2) = generateKeyPair(KEY_TYPE.ECDSA)
        var bb1: ByteBuf? = null
        var bb2: ByteBuf? = null

        val hs1 = SecioHandshake({ bb -> bb1 = bb }, privKey1, PeerId.fromPubKey(pubKey2))
        val hs2 = SecioHandshake({ bb -> bb2 = bb }, privKey2, PeerId.fromPubKey(pubKey1))

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

        val plainMsg = "Hello".toByteArray()
        val tmp = ByteArray(plainMsg.size)
        SecIoCodec.createCipher(keys1!!.first).processBytes(plainMsg, 0, plainMsg.size, tmp, 0)
        SecIoCodec.createCipher(keys2!!.second).processBytes(tmp, 0, tmp.size, tmp, 0)

        Assertions.assertArrayEquals(plainMsg, tmp)

        SecIoCodec.createCipher(keys2.first).processBytes(plainMsg, 0, plainMsg.size, tmp, 0)
        SecIoCodec.createCipher(keys1.second).processBytes(tmp, 0, tmp.size, tmp, 0)

        Assertions.assertArrayEquals(plainMsg, tmp)
    }

    @Test
    fun testSecpPub() {
        val pubKey = secp256k1PublicKeyFromCoordinates(
            BigInteger("29868384252041196073668708557917617583643409287860779134116445706780092854384"),
            BigInteger("32121030900263138255369578555559933217781286061089165917390620197021766129989")
        )
        val peerId = PeerId.fromPubKey(pubKey)
        Assertions.assertEquals("16Uiu2HAmH6m8pE7pXsfzXHg3Z5kLzgwJ5PWSYELaKXc75Bs2utZM", peerId.toBase58())

        val serializedGoKey = """
            08 02 12 20 6F D7 AF 84 82 36 61 AE 4F 0F DB 05
            D8 3B D5 56 9B 42 70 FE 94 C8 AD 79 CC B7 E3 F8
            43 DE FC B1
        """.trimIndent()
            .replace(" ", "")
            .replace("\n", "")

        val privKey = unmarshalPrivateKey(serializedGoKey.fromHex())
        println()
        Assertions.assertEquals("16Uiu2HAmH6m8pE7pXsfzXHg3Z5kLzgwJ5PWSYELaKXc75Bs2utZM",
            PeerId.fromPubKey(privKey.publicKey()).toBase58())
    }
}