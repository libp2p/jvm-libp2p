package io.libp2p.core.security.noise

import com.google.protobuf.ByteString
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import io.libp2p.core.Connection
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.tools.TestHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spipe.pb.Spipe
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

        // identity
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, _) = generateKeyPair(KEY_TYPE.ECDSA)

        // noise keys
        val aliceDHState = Noise.createDH("25519")
        val bobDHState = Noise.createDH("25519")
        aliceDHState.generateKeyPair()
        bobDHState.generateKeyPair()
        val ch1 = NoiseSecureChannel(privKey1, aliceDHState, bobDHState, HandshakeState.INITIATOR)
        val ch2 = NoiseSecureChannel(privKey2, bobDHState, aliceDHState, HandshakeState.RESPONDER)

        var rec1: String? = ""
        var rec2: String? = ""
        val latch = CountDownLatch(2)


        val eCh1 = TestChannel(LoggingHandler("#1", LogLevel.ERROR),
            object : TestHandler("1") {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
                    msg as ByteBuf
//                    rec1 = msg.toByteArray().toString(StandardCharsets.UTF_8)
                    rec1 = ctx.channel().attr(NoiseSecureChannel.dataAttribute).toString()
                    logger.debug("==$name== read111: $rec1")
                    latch.countDown()
                }
            })
        val eCh2 = TestChannel(LoggingHandler("#2", LogLevel.ERROR),
            object : TestHandler("2") {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
                    msg as ByteBuf
                    rec2 = msg.toString(StandardCharsets.UTF_8)
                    logger.debug("==$name== read: $rec2")
                    latch.countDown()
                }
            })
        ch1.initChannel(Connection(eCh1));
        ch2.initChannel(Connection(eCh2));
        interConnect(eCh1, eCh2)
        eCh2.write("test".toByteArray())

        latch.await(10, TimeUnit.SECONDS)

        Assertions.assertEquals("Hello World", rec2)
        Assertions.assertEquals("Hello World", rec1)
    }

    @Test
    fun testAnnounceAndMatch() {
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)

        // noise keys
        val aliceDHState = Noise.createDH("25519")
        val bobDHState = Noise.createDH("25519")
        aliceDHState.generateKeyPair()
        bobDHState.generateKeyPair()
        val ch1 = NoiseSecureChannel(privKey1, aliceDHState, bobDHState, HandshakeState.INITIATOR)

        val announce = ch1.announce
        val matcher = ch1.matcher
        assertTrue(matcher.matches(announce))
    }

    @Test
    fun testFallbackProtocol() {
        // TODO
    }

    companion object {
        private val logger = LogManager.getLogger(NoiseSecureChannelTest::class.java)
    }
}


fun interConnect(ch1: TestChannel, ch2: TestChannel) {
    ch1.connect(ch2)
    ch2.connect(ch1)
}

class TestChannel(vararg handlers: ChannelHandler?) : EmbeddedChannel(*handlers) {
    var link: TestChannel? = null
    val executor = Executors.newSingleThreadExecutor()

    @Synchronized
    fun connect(other: TestChannel) {
        link = other
        outboundMessages().forEach(this::send)
    }

    @Synchronized
    override fun handleOutboundMessage(msg: Any?) {
        super.handleOutboundMessage(msg)
        if (link != null) {
            send(msg!!)
        }
    }

    fun send(msg: Any) {
        executor.execute {
            logger.debug("---- link!!.writeInbound")
            link!!.writeInbound(msg)
        }
    }

    companion object {
        private val logger = LogManager.getLogger(TestChannel::class.java)
    }
}