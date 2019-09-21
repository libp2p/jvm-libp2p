package io.libp2p.security.noise

import com.google.protobuf.ByteString
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.etc.SECURE_SESSION
import io.libp2p.etc.types.toByteBuf
import io.libp2p.multistream.Negotiator
import io.libp2p.multistream.ProtocolSelect
import io.libp2p.tools.TestChannel.Companion.interConnect
import io.libp2p.tools.TestHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spipe.pb.Spipe
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NoiseSecureChannelTest {
    // tests for Noise

    var alice_hs: HandshakeState? = null
    var bob_hs: HandshakeState? = null

    @Test
    fun test1() {
        // test1
        // Noise framework initialization

        alice_hs = HandshakeState("Noise_IK_25519_ChaChaPoly_SHA256", HandshakeState.INITIATOR)
        bob_hs = HandshakeState("Noise_IK_25519_ChaChaPoly_SHA256", HandshakeState.RESPONDER)

        assertNotNull(alice_hs)
        assertNotNull(bob_hs)

        if (alice_hs!!.needsLocalKeyPair()) {
            val localKeyPair = alice_hs!!.localKeyPair
            localKeyPair.generateKeyPair()

            val prk = ByteArray(localKeyPair.privateKeyLength)
            val puk = ByteArray(localKeyPair.publicKeyLength)
            localKeyPair.getPrivateKey(prk, 0)
            localKeyPair.getPublicKey(puk, 0)

            assert(prk.max()?.compareTo(0) != 0)
            assert(puk.max()?.compareTo(0) != 0)
            assert(alice_hs!!.hasLocalKeyPair())
        }

        if (bob_hs!!.needsLocalKeyPair()) {
            bob_hs!!.localKeyPair.generateKeyPair()
        }

        if (alice_hs!!.needsRemotePublicKey() || bob_hs!!.needsRemotePublicKey()) {
            alice_hs!!.remotePublicKey.copyFrom(bob_hs!!.localKeyPair)
            bob_hs!!.remotePublicKey.copyFrom(alice_hs!!.localKeyPair)

            assert(alice_hs!!.hasRemotePublicKey())
            assert(bob_hs!!.hasRemotePublicKey())
        }
    }

    @Test
    fun test2() {
        // protocol starts and respective resulting state
        test1()

        alice_hs!!.start()
        bob_hs!!.start()

        assert(alice_hs!!.action != HandshakeState.FAILED)
        assert(bob_hs!!.action != HandshakeState.FAILED)
    }

    @Test
    fun test3() {
        test2()
        // - creation of initiator ephemeral key

        // ephemeral keys become part of the Noise protocol instance
        // Noise currently hides this part of the protocol
        // test by testing later parts of the protocol

        // after a successful communication of responder information
        // need to construct DH parameters of form se and ee

        val aliceSendBuffer = ByteArray(65535)
        val aliceMsgLength: Int

        val bobSendBuffer = ByteArray(65535)
        val bobMsgLength: Int

        val payload = ByteArray(65535)

        aliceMsgLength = alice_hs!!.writeMessage(aliceSendBuffer, 0, payload, 0, 0)
        bob_hs!!.readMessage(aliceSendBuffer, 0, aliceMsgLength, payload, 0)
        bobMsgLength = bob_hs!!.writeMessage(bobSendBuffer, 0, payload, 0, 0)
        alice_hs!!.readMessage(bobSendBuffer, 0, bobMsgLength, payload, 0)

        // at split state
        val aliceSplit = alice_hs!!.split()
        val bobSplit = bob_hs!!.split()

        val acipher = ByteArray(65535)
        val acipherLength: Int
        val bcipher = ByteArray(65535)
        val bcipherLength: Int
        val s1 = "Hello World!"
        acipherLength = aliceSplit.sender.encryptWithAd(null, s1.toByteArray(), 0, acipher, 0, s1.length)
        bcipherLength = bobSplit.receiver.decryptWithAd(null, acipher, 0, bcipher, 0, acipherLength)

        assert(s1.toByteArray().contentEquals(bcipher.copyOfRange(0, bcipherLength)))
        assert(alice_hs!!.action == HandshakeState.COMPLETE)
        assert(bob_hs!!.action == HandshakeState.COMPLETE)
    }

    @Test
    fun test4() {
        test2()
        // generate a Peer Identity protobuf object
        // use it for encoding and decoding peer identities from the wire
        // this identity is intended to be sent as a Noise transport payload
        val (privKey, pubKey) = generateKeyPair(KEY_TYPE.ECDSA)
        assert(pubKey.bytes().max()?.compareTo(0) != 0)

        // sign the identity using the identity's private key
        val signed = privKey.sign(pubKey.bytes())
        // the signed bytes become the payload for the first handshake write message

        // generate an appropriate protobuf element
        val bs = Spipe.Exchange.newBuilder().setEpubkey(ByteString.copyFrom(pubKey.bytes()))
            .setSignature(ByteString.copyFrom(signed)).build()

        val msgBuffer = ByteArray(65535)
        val msgLength = alice_hs!!.writeMessage(msgBuffer, 0, bs.toByteArray(), 0, bs.toByteArray().size)

        assert(msgLength > 0)
        assert(msgBuffer.max()?.compareTo(0) != 0)
    }

    @Test
    fun testNoiseChannelThroughEmbedded() {
        // test Noise secure channel through embedded channels
        logger.debug("Beginning embedded test")

        // node keys
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, _) = generateKeyPair(KEY_TYPE.ECDSA)

        val privateKey25519_1 = ByteArray(32)
        Noise.random(privateKey25519_1)
        val privateKey25519_2 = ByteArray(32)
        Noise.random(privateKey25519_2)

        // noise keys
        val ch1 = NoiseXXSecureChannel(privKey1, privateKey25519_1)
        val ch2 = NoiseXXSecureChannel(privKey2, privateKey25519_2)

        val protocolSelect1 = ProtocolSelect(listOf(ch1))
        val protocolSelect2 = ProtocolSelect(listOf(ch2))

        val eCh1 = io.libp2p.tools.TestChannel("#1", true, LoggingHandler("#1", LogLevel.ERROR),
            Negotiator.createRequesterInitializer(NoiseXXSecureChannel.announce),
            protocolSelect1)

        val eCh2 = io.libp2p.tools.TestChannel("#2", false,
            LoggingHandler("#2", LogLevel.ERROR),
            Negotiator.createResponderInitializer(listOf(ProtocolMatcher(Mode.STRICT, NoiseXXSecureChannel.announce))),
            protocolSelect2)

        logger.debug("Connecting initial channels")
        interConnect(eCh1, eCh2)

        logger.debug("Waiting for negotiation to complete...")
        protocolSelect1.selectedFuture.get(10, TimeUnit.SECONDS)
        protocolSelect2.selectedFuture.get(10, TimeUnit.SECONDS)
        logger.debug("Secured!")

        var rec1: String? = ""
        var rec2: String? = ""
        val latch = CountDownLatch(2)

        // Setup alice's pipeline. TestHandler handles inbound data, so chanelActive() is used to write to the context
        eCh1.pipeline().addLast(object : TestHandler("1") {
//            override fun channelRegistered(ctx: ChannelHandlerContext?) {
//                channelActive(ctx!!)
//            }
//
//            override fun channelActive(ctx: ChannelHandlerContext) {
//                val get: NoiseSecureChannelSession = ctx.channel().attr(SECURE_SESSION).get() as NoiseSecureChannelSession
//                var additionalData = ByteArray(65535)
//                var cipherText = ByteArray(65535)
//                var plaintext = "Hello World from $name".toByteArray()
//                var length = get.aliceCipher.encryptWithAd(additionalData, plaintext, 0, cipherText, 2, plaintext.size)
//                logger.debug("encrypt length:" + length)
//                ctx.writeAndFlush(Arrays.copyOf(cipherText, length + 2).toByteBuf().setShort(0, length))
//            }

            override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
                rec1=msg as String
                logger.debug("==$name== read: $msg")
                latch.countDown()
            }
//            override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
//                msg as ByteBuf
//
//                val get: NoiseSecureChannelSession = ctx.channel().attr(SECURE_SESSION).get() as NoiseSecureChannelSession
//                var additionalData = ByteArray(65535)
//                var plainText = ByteArray(65535)
//                var cipherText = msg.toByteArray()
//                var length = msg.getShort(0).toInt()
//                logger.debug("decrypt length:" + length)
//                var l = get.bobCipher.decryptWithAd(additionalData, cipherText, 2, plainText, 0, length)
//                rec1 = plainText.copyOf(l).toString(StandardCharsets.UTF_8)
//                logger.debug("==$name== read: $rec1")
//                latch.countDown()
//            }
        })

        // Setup bob's pipeline
        eCh2.pipeline().addLast(object : TestHandler("2") {
//            override fun channelRegistered(ctx: ChannelHandlerContext?) {
//                channelActive(ctx!!)
//                super.channelRegistered(ctx)
//            }
//
//            override fun channelActive(ctx: ChannelHandlerContext) {
//                val get: NoiseSecureChannelSession = ctx.channel().attr(SECURE_SESSION).get() as NoiseSecureChannelSession
//                var additionalData = ByteArray(65535)
//                var cipherText = ByteArray(65535)
//                var plaintext = "Hello World from $name".toByteArray()
//                var length = get.aliceCipher.encryptWithAd(additionalData, plaintext, 0, cipherText, 2, plaintext.size)
//                logger.debug("encrypt length:" + length)
//                ctx.writeAndFlush(Arrays.copyOf(cipherText, length + 2).toByteBuf().setShort(0, length))
//            }


            override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
                rec2 = msg as String
                logger.debug("==$name== read: $msg")
                latch.countDown()
            }

//            override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
//                msg as ByteBuf
//
//                val get: NoiseSecureChannelSession = ctx.channel().attr(SECURE_SESSION).get() as NoiseSecureChannelSession
//                var additionalData = ByteArray(65535)
//                var plainText = ByteArray(65535)
//                var cipherText = msg.toByteArray()
//                var length = msg.getShort(0).toInt()
//                logger.debug("decrypt length:" + length)
//                var l = get.bobCipher.decryptWithAd(additionalData, cipherText, 2, plainText, 0, length)
//                rec2 = plainText.copyOf(l).toString(StandardCharsets.UTF_8)
//                logger.debug("==$name== read: $rec2")
//                latch.countDown()
//            }
        })

//        eCh1.pipeline().fireChannelRegistered()
//        eCh2.pipeline().fireChannelRegistered()
        eCh1.pipeline().firstContext().writeAndFlush("Hello World from 1")
        eCh2.pipeline().firstContext().writeAndFlush("Hello World from 2")

        latch.await(10, TimeUnit.SECONDS)

        Assertions.assertEquals("Hello World from 1", rec2)
        Assertions.assertEquals("Hello World from 2", rec1)

        System.gc()
        Thread.sleep(500)
        System.gc()
        Thread.sleep(500)
        System.gc()
    }

    @Test
    fun testAnnounceAndMatch() {
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)

        val privateKey25519_1 = ByteArray(32)
        Noise.random(privateKey25519_1)

        val ch1 =
            NoiseXXSecureChannel(privKey1, privateKey25519_1)

        val announce = ch1.announce
        val matcher = ch1.matcher
        assertTrue(matcher.matches(announce))
    }

    companion object {
        private val logger = LogManager.getLogger(NoiseSecureChannelTest::class.java)
    }
}