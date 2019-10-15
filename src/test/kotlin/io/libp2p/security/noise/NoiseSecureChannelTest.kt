package io.libp2p.security.noise

import com.google.protobuf.ByteString
import com.southernstorm.noise.protocol.HandshakeState
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.etc.types.toByteArray
import io.libp2p.multistream.Negotiator
import io.libp2p.multistream.ProtocolSelect
import io.libp2p.tools.TestChannel.Companion.interConnect
import io.libp2p.tools.TestHandler
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.Configurator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import spipe.pb.Spipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NoiseSecureChannelTest {
    // tests for Noise

    private lateinit var aliceHS: HandshakeState
    private lateinit var bobHS: HandshakeState

    @Test
    fun test1() {
        // test1
        // Noise framework initialization

        aliceHS = HandshakeState("Noise_IK_25519_ChaChaPoly_SHA256", HandshakeState.INITIATOR)
        bobHS = HandshakeState("Noise_IK_25519_ChaChaPoly_SHA256", HandshakeState.RESPONDER)

        Assertions.assertNotNull(aliceHS)
        Assertions.assertNotNull(bobHS)

        if (aliceHS.needsLocalKeyPair()) {
            val localKeyPair = aliceHS.localKeyPair
            localKeyPair.generateKeyPair()

            val prk = ByteArray(localKeyPair.privateKeyLength)
            val puk = ByteArray(localKeyPair.publicKeyLength)
            localKeyPair.getPrivateKey(prk, 0)
            localKeyPair.getPublicKey(puk, 0)

            assert(prk.max()?.compareTo(0) != 0)
            assert(puk.max()?.compareTo(0) != 0)
            assert(aliceHS.hasLocalKeyPair())
        }

        if (bobHS.needsLocalKeyPair()) {
            bobHS.localKeyPair.generateKeyPair()
        }

        if (aliceHS.needsRemotePublicKey() || bobHS.needsRemotePublicKey()) {
            aliceHS.remotePublicKey.copyFrom(bobHS.localKeyPair)
            bobHS.remotePublicKey.copyFrom(aliceHS.localKeyPair)

            assert(aliceHS.hasRemotePublicKey())
            assert(bobHS.hasRemotePublicKey())
        }
    }

    @Test
    fun test2() {
        // protocol starts and respective resulting state
        test1()

        aliceHS.start()
        bobHS.start()

        assert(aliceHS.action != HandshakeState.FAILED)
        assert(bobHS.action != HandshakeState.FAILED)
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

        aliceMsgLength = aliceHS.writeMessage(aliceSendBuffer, 0, payload, 0, 0)
        bobHS.readMessage(aliceSendBuffer, 0, aliceMsgLength, payload, 0)
        bobMsgLength = bobHS.writeMessage(bobSendBuffer, 0, payload, 0, 0)
        aliceHS.readMessage(bobSendBuffer, 0, bobMsgLength, payload, 0)

        // at split state
        val aliceSplit = aliceHS.split()
        val bobSplit = bobHS.split()

        val acipher = ByteArray(65535)
        val acipherLength: Int
        val bcipher = ByteArray(65535)
        val bcipherLength: Int
        val s1 = "Hello World!"
        acipherLength = aliceSplit.sender.encryptWithAd(null, s1.toByteArray(), 0, acipher, 0, s1.length)
        bcipherLength = bobSplit.receiver.decryptWithAd(null, acipher, 0, bcipher, 0, acipherLength)

        assert(s1.toByteArray().contentEquals(bcipher.copyOfRange(0, bcipherLength)))
        assert(aliceHS.action == HandshakeState.COMPLETE)
        assert(bobHS.action == HandshakeState.COMPLETE)
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
        val bs = Spipe.NoiseHandshakePayload.newBuilder()
            .setLibp2PKey(ByteString.copyFrom(pubKey.bytes()))
            .setNoiseStaticKeySignature(ByteString.copyFrom(signed))
            .setLibp2PData(ByteString.EMPTY)
            .setLibp2PDataSignature(ByteString.EMPTY)
            .build()

        val msgBuffer = ByteArray(65535)
        val msgLength = aliceHS.writeMessage(msgBuffer, 0, bs.toByteArray(), 0, bs.toByteArray().size)

        assert(msgLength > 0)
        assert(msgBuffer.max()?.compareTo(0) != 0)
    }

    @Test
    fun testNoiseChannelThroughEmbedded() {
        // test Noise secure channel through embedded channels
        logger.debug("Beginning embedded test")

        // node keys
        val (privKeyAlicePeer, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKeyBobPeer, _) = generateKeyPair(KEY_TYPE.ECDSA)

        // noise keys
        val ch1 = NoiseXXSecureChannel(privKeyAlicePeer)
        val ch2 = NoiseXXSecureChannel(privKeyBobPeer)

        val protocolSelect1 = ProtocolSelect(listOf(ch1))
        val protocolSelect2 = ProtocolSelect(listOf(ch2))

        val eCh1 = io.libp2p.tools.TestChannel(
            "#1", true, LoggingHandler("#1", LogLevel.ERROR),
            Negotiator.createRequesterInitializer(NoiseXXSecureChannel.announce),
            protocolSelect1
        )

        val eCh2 = io.libp2p.tools.TestChannel(
            "#2", false,
            LoggingHandler("#2", LogLevel.ERROR),
            Negotiator.createResponderInitializer(listOf(ProtocolMatcher(Mode.STRICT, NoiseXXSecureChannel.announce))),
            protocolSelect2
        )

        logger.debug("Connecting initial channels")
        interConnect(eCh1, eCh2)

        logger.debug("Waiting for negotiation to complete...")
        protocolSelect1.selectedFuture.get(10, TimeUnit.SECONDS)
        protocolSelect2.selectedFuture.get(10, TimeUnit.SECONDS)
        logger.debug("Secured!")

        var rec1: String? = ""
        var rec2: String? = ""
        val latch = CountDownLatch(2)

        // Setup alice's pipeline
        eCh1.pipeline().addLast(object : TestHandler("1") {
            override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
                msg as ByteBuf
                rec1 = String(msg.toByteArray())
                logger.debug("==$name== read: $msg")
                latch.countDown()
            }
        })

        // Setup bob's pipeline
        eCh2.pipeline().addLast(object : TestHandler("2") {
            override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
                msg as ByteBuf
                rec2 = String(msg.toByteArray())
                logger.debug("==$name== read: $msg")
                latch.countDown()
            }
        })

        eCh1.writeAndFlush(Unpooled.wrappedBuffer("Hello World from 1".toByteArray()))
        eCh2.writeAndFlush(Unpooled.wrappedBuffer("Hello World from 2".toByteArray()))

        latch.await(5, TimeUnit.SECONDS)

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

        val ch1 = NoiseXXSecureChannel(privKey1)

        val announce = ch1.announce
        val matcher = ch1.matcher
        Assertions.assertTrue(matcher.matches(announce))
    }

    @Test
    fun testStaticNoiseKeyPerProcess() {
        System.out.println("Starting static key test")
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)
        NoiseXXSecureChannel(privKey1)
        val b1 = NoiseXXSecureChannel.localStaticPrivateKey25519.copyOf()

        val (privKey2, _) = generateKeyPair(KEY_TYPE.ECDSA)
        NoiseXXSecureChannel(privKey2)
        val b2 = NoiseXXSecureChannel.localStaticPrivateKey25519.copyOf()

        Assertions.assertTrue(b1.contentEquals(b2), "NoiseXX static keys are not maintained between sessions.")
        System.out.println("Finished static key test")
    }

    companion object {
        private val logger = LogManager.getLogger(NoiseSecureChannelTest::class.java.name)
    }

    init {
        Configurator.setLevel(NoiseSecureChannelTest::class.java.name, Level.DEBUG)
    }
}