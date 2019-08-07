package io.libp2p.core.security.noise

import com.southernstorm.noise.protocol.HandshakeState
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.netty.channel.ChannelHandler
import io.netty.channel.embedded.EmbeddedChannel
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class NoiseSecureChannelTest {
    // tests for Noise

    // TODO
    // protocol matcher and announcer
    // TestChannel usage
    // read and write message
    var alice_hs: HandshakeState? = null
    var bob_hs: HandshakeState? = null

    @Test
    fun test1() {
        // test1
        // Noise framework initialization
        // initiator keys
        // responder keys

        // check that 'peers' started successfully

        alice_hs = HandshakeState("Noise_IK_25519_ChaChaPoly_SHA256", HandshakeState.INITIATOR)
        bob_hs = HandshakeState("Noise_IK_25519_ChaChaPoly_SHA256", HandshakeState.RESPONDER)

        assertNotNull(alice_hs)
        assertNotNull(bob_hs)

        // depends on protocol being executed

        // - initiator public key and private key
        // - responder public key

        if (alice_hs!!.needsLocalKeyPair()) {
            val localKeyPair = alice_hs!!.localKeyPair
            localKeyPair.generateKeyPair()

            val prk = ByteArray(localKeyPair.privateKeyLength)
            val puk = ByteArray(localKeyPair.publicKeyLength)
            localKeyPair.getPrivateKey(prk, 0)
            localKeyPair.getPublicKey(puk, 0)

            println("prk:" + prk.toList())
            println("puk:" + puk.toList())

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

        println("handshakes started...")
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

        var iteration = 0;

        val aliceSendBuffer = ByteArray(65535)
        var aliceMsgLength = 0

        val bobSendBuffer = ByteArray(65535)
        var bobMsgLength = 0

        val payload = ByteArray(65535)


        reportHSStates(aliceMsgLength, aliceSendBuffer, bobMsgLength, bobSendBuffer)
        aliceMsgLength = alice_hs!!.writeMessage(aliceSendBuffer, 0, payload, 0, 0)
        reportHSStates(aliceMsgLength, aliceSendBuffer, bobMsgLength, bobSendBuffer)

        bob_hs!!.readMessage(aliceSendBuffer, 0, aliceMsgLength, payload, 0)
        reportHSStates(aliceMsgLength, aliceSendBuffer, bobMsgLength, bobSendBuffer)

        bobMsgLength = bob_hs!!.writeMessage(bobSendBuffer, 0, payload, 0, 0)
        reportHSStates(aliceMsgLength, aliceSendBuffer, bobMsgLength, bobSendBuffer)

        alice_hs!!.readMessage(bobSendBuffer, 0, bobMsgLength, payload, 0)
        reportHSStates(aliceMsgLength, aliceSendBuffer, bobMsgLength, bobSendBuffer)

        // at split state
        val aliceSplit = alice_hs!!.split()
        val bobSplit = bob_hs!!.split()

        val acipher = ByteArray(65535)
        var acipherLength = 0
        val bcipher = ByteArray(65535)
        var bcipherLength = 0
        val s1 = "Hello World!"
        val s2 = "hello world"
        println(s1.toByteArray().asList())
        acipherLength = aliceSplit.sender.encryptWithAd(null, s1.toByteArray(), 0, acipher, 0, s1.length)
        bcipherLength = bobSplit.receiver.decryptWithAd(null, acipher, 0, bcipher, 0, acipherLength)
        println("bcipher:" + bcipher.copyOfRange(0,bcipherLength).asList())

        assert(s1.toByteArray().contentEquals(bcipher.copyOfRange(0,bcipherLength)))
        println("bcipher string:"+String(bcipher.copyOfRange(0,bcipherLength)))
    }

    private fun reportHSStates(aliceMsgLength: Int, aliceSendBuffer: ByteArray, bobMsgLength: Int, bobSendBuffer: ByteArray) {
        println("-")
        println("a_msgLength:$aliceMsgLength")
        println("a_msg:" + aliceSendBuffer.asList())
        println("b_msgLength:$bobMsgLength")
        println("b_msg:" + bobSendBuffer.asList())

        println("1a:" + alice_hs!!.action)
        println("1b:" + bob_hs!!.action)

        println("---")
    }

    @Test
    fun test4() {
        test2()
        // generate a Peer Identity protobuf object
        // use it for encoding and decoding peer identities from the wire
        // this identity is intended to be sent as a Noise transport payload
        val (privKey, pubKey) = generateKeyPair(KEY_TYPE.ECDSA)
        println("pubkey:" + pubKey.bytes().asList())
        assert(pubKey.bytes().max()?.compareTo(0) != 0)

        // sign the identity using the identity's private key
        val signed = privKey.sign(pubKey.bytes())
        // the signed bytes become the payload for the first handshake write message

        val msgBuffer = ByteArray(65535)
        val msgLength = alice_hs!!.writeMessage(msgBuffer, 0, signed, 0, signed.size)

        println("msgBuffer2:" + msgBuffer.asList())
        println("msgBuffer2length:$msgLength")
        assert(msgLength > 0)
        assert(msgBuffer.max()?.compareTo(0) != 0)
    }

    companion object {
        private val logger = LogManager.getLogger(NoiseSecureChannelTest::class.java)
    }
}


fun interConnect(ch1: io.libp2p.core.security.secio.TestChannel, ch2: io.libp2p.core.security.secio.TestChannel) {
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