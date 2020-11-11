package io.libp2p.security.secio

import com.google.protobuf.Message
import com.google.protobuf.Parser
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.sha256
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.crypto.StretchedKey
import io.libp2p.crypto.keys.EcdsaPrivateKey
import io.libp2p.crypto.keys.EcdsaPublicKey
import io.libp2p.crypto.keys.decodeEcdsaPublicKeyUncompressed
import io.libp2p.crypto.keys.generateEcdsaKeyPair
import io.libp2p.crypto.stretchKeys
import io.libp2p.etc.types.compareTo
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toProtobuf
import io.libp2p.security.InvalidInitialPacket
import io.libp2p.security.InvalidRemotePubKey
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import spipe.pb.Spipe
import java.security.SecureRandom

data class SecioParams(
    val permanentPubKey: PubKey,
    val keys: StretchedKey,

    val mac: HMac
)

/**
 * Created by Anton Nashatyrev on 14.06.2019.
 */
class SecIoNegotiator(
    private val outboundChannel: (ByteBuf) -> Unit,
    val localKey: PrivKey,
    val remotePeerId: PeerId?
) {

    enum class State {
        Initial,
        ProposeSent,
        ExchangeSent,
        KeysCreated,
        SecureChannelCreated,
        FinalValidated
    }

    private var state = State.Initial

    private val random = SecureRandom()
    private val nonceSize = 16
    private val ciphers = linkedSetOf("AES-128", "AES-256")
    private val hashes = linkedSetOf("SHA256", "SHA512")
    private val curves = linkedSetOf("P-256", "P-384", "P-521")

    private val nonce = ByteArray(nonceSize).apply { random.nextBytes(this) }
    private lateinit var proposeMsg: Spipe.Propose
    private lateinit var remotePropose: Spipe.Propose
    private lateinit var remotePubKey: PubKey
    private var order: Int? = null
    private lateinit var curve: String
    private lateinit var hash: String
    private lateinit var cipher: String
    private lateinit var ephPrivKey: EcdsaPrivateKey
    private lateinit var ephPubKey: EcdsaPublicKey

    private val localPubKeyBytes = localKey.publicKey().bytes()
    private val remoteNonce: ByteArray
        get() = remotePropose.rand.toByteArray()

    fun isComplete() = state == State.FinalValidated

    fun start() {
        proposeMsg = Spipe.Propose.newBuilder()
            .setRand(nonce.toProtobuf())
            .setPubkey(localPubKeyBytes.toProtobuf())
            .setExchanges(curves.joinToString(","))
            .setHashes(hashes.joinToString(","))
            .setCiphers(ciphers.joinToString(","))
            .build()

        state = State.ProposeSent
        write(proposeMsg)
    }

    fun onSecureChannelSetup() {
        if (state != State.KeysCreated) throw InvalidNegotiationState()

        write(remoteNonce)

        state = State.SecureChannelCreated
    }

    fun onNewMessage(buf: ByteBuf): Pair<SecioParams, SecioParams>? {
        when (state) {
            State.ProposeSent ->
                verifyRemoteProposal(buf)
            State.ExchangeSent ->
                return verifyKeyExchange(buf)
            State.SecureChannelCreated ->
                verifyNonceResponse(buf)
            else ->
                throw InvalidNegotiationState()
        }
        return null
    }

    private fun verifyRemoteProposal(buf: ByteBuf) {
        remotePropose = read(buf, Spipe.Propose.parser())

        val remotePubKeyBytes = remotePropose.pubkey.toByteArray()
        remotePubKey = validateRemoteKey(remotePubKeyBytes)

        order = orderKeys(remotePubKeyBytes)

        curve = selectCurve()
        hash = selectHash()
        cipher = selectCipher()

        val (ephPrivKeyL, ephPubKeyL) = generateEcdsaKeyPair(curve)
        ephPrivKey = ephPrivKeyL
        ephPubKey = ephPubKeyL

        val exchangeMsg = buildExchangeMessage()

        state = State.ExchangeSent
        write(exchangeMsg)
    } // verifyRemoteProposal

    private fun validateRemoteKey(remotePubKeyBytes: ByteArray): PubKey {
        val pubKey = unmarshalPublicKey(remotePubKeyBytes)

        val calcedPeerId = PeerId.fromPubKey(pubKey)
        if (remotePeerId != null && calcedPeerId != remotePeerId)
            throw InvalidRemotePubKey()

        return pubKey
    } // validateRemoteKey

    private fun orderKeys(remotePubKeyBytes: ByteArray): Int {
        val h1 = sha256(remotePubKeyBytes + nonce)
        val h2 = sha256(localPubKeyBytes + remoteNonce)

        val keyOrder = h1.compareTo(h2)
        if (keyOrder == 0)
            throw SelfConnecting()

        return keyOrder
    } // orderKeys

    private fun buildExchangeMessage(): Spipe.Exchange {
        return Spipe.Exchange.newBuilder()
            .setEpubkey(ephPubKey.toUncompressedBytes().toProtobuf())
            .setSignature(
                localKey.sign(
                    proposeMsg.toByteArray() +
                        remotePropose.toByteArray() +
                        ephPubKey.toUncompressedBytes()
                ).toProtobuf()
            ).build()
    } // buildExchangeMessage

    private fun verifyKeyExchange(buf: ByteBuf): Pair<SecioParams, SecioParams> {
        val remoteExchangeMsg = read(buf, Spipe.Exchange.parser())
        validateExchangeMessage(remoteExchangeMsg)

        val sharedSecret = generateSharedSecret(remoteExchangeMsg)

        val (k1, k2) = stretchKeys(cipher, hash, sharedSecret)

        val localKeys = selectFirst(k1, k2)
        val remoteKeys = selectSecond(k1, k2)

        state = State.KeysCreated
        return Pair(
            SecioParams(
                localKey.publicKey(),
                localKeys,
                calcHMac(localKeys.macKey)
            ),
            SecioParams(
                remotePubKey,
                remoteKeys,
                calcHMac(remoteKeys.macKey)
            )
        )
    } // verifyKeyExchange

    private fun validateExchangeMessage(exchangeMsg: Spipe.Exchange) {
        val signatureIsOk = remotePubKey.verify(
            remotePropose.toByteArray() +
                proposeMsg.toByteArray() +
                exchangeMsg.epubkey.toByteArray(),
            exchangeMsg.signature.toByteArray()
        )

        if (!signatureIsOk)
            throw InvalidSignature()
    } // validateExchangeMessage

    private fun calcHMac(macKey: ByteArray): HMac {
        val hmac = when (hash) {
            "SHA256" -> HMac(SHA256Digest())
            "SHA512" -> HMac(SHA512Digest())
            else -> throw IllegalArgumentException("Unsupported hash function: $hash")
        }
        hmac.init(KeyParameter(macKey))
        return hmac
    } // calcHMac

    private fun generateSharedSecret(exchangeMsg: Spipe.Exchange): ByteArray {
        val ecCurve = ECNamedCurveTable.getParameterSpec(curve).curve

        val remoteEphPublickKey =
            decodeEcdsaPublicKeyUncompressed(
                curve,
                exchangeMsg.epubkey.toByteArray()
            )
        val remoteEphPubPoint =
            ecCurve.validatePoint(
                remoteEphPublickKey.pub.w.affineX,
                remoteEphPublickKey.pub.w.affineY
            )

        val sharedSecretPoint = ecCurve.multiplier.multiply(
            remoteEphPubPoint,
            ephPrivKey.priv.s
        )

        val sharedSecret = sharedSecretPoint.normalize().affineXCoord.encoded
        return sharedSecret
    } // generateSharedSecret

    private fun verifyNonceResponse(buf: ByteBuf) {
        if (!nonce.contentEquals(buf.toByteArray()))
            throw InvalidInitialPacket()

        state = State.FinalValidated
    } // verifyNonceResponse

    private fun write(outMsg: Message) {
        val byteBuf = Unpooled.buffer().writeBytes(outMsg.toByteArray())
        outboundChannel.invoke(byteBuf)
    }
    private fun write(msg: ByteArray) {
        outboundChannel.invoke(msg.toByteBuf())
    }

    private fun <MessageType : Message> read(buf: ByteBuf, parser: Parser<MessageType>): MessageType {
        return parser.parseFrom(buf.nioBuffer())
    }

    private fun selectCurve(): String {
        return selectBest(curves, remotePropose.exchanges.split(","))
    } // selectCurve
    private fun selectHash(): String {
        return selectBest(hashes, remotePropose.hashes.split(","))
    }
    private fun selectCipher(): String {
        return selectBest(ciphers, remotePropose.ciphers.split(","))
    }

    private fun selectBest(
        p1: Collection<String>,
        p2: Collection<String>
    ): String {
        val intersect =
            linkedSetOf(*(selectFirst(p1, p2)).toTypedArray())
                .intersect(linkedSetOf(*(selectSecond(p1, p2)).toTypedArray()))
        if (intersect.isEmpty()) throw NoCommonAlgos()
        return intersect.first()
    } // selectBest

    private fun <T> selectFirst(lhs: T, rhs: T) = if (order!! > 0) lhs else rhs
    private fun <T> selectSecond(lhs: T, rhs: T) = if (order!! > 0) rhs else lhs
} // class SecioNegotiator
