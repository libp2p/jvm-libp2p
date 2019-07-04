package io.libp2p.core.security.secio

import com.google.protobuf.Message
import com.google.protobuf.Parser
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.StretchedKey
import io.libp2p.core.crypto.keys.EcdsaPrivateKey
import io.libp2p.core.crypto.keys.EcdsaPublicKey
import io.libp2p.core.crypto.keys.decodeEcdsaPublicKeyUncompressed
import io.libp2p.core.crypto.keys.generateEcdsaKeyPair
import io.libp2p.core.crypto.sha256
import io.libp2p.core.crypto.stretchKeys
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.types.compareTo
import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toByteBuf
import io.libp2p.core.types.toProtobuf
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import spipe.pb.Spipe
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SecioParams(
    val nonce: ByteArray,
    val permanentPubKey: PubKey,
    val ephemeralPubKey: ByteArray,
    val keys: StretchedKey,

    val curveT: String,
    val cipherT: String,
    val hashT: String,

    val mac: HMac,
    val cipher: Cipher
)

/**
 * Created by Anton Nashatyrev on 14.06.2019.
 */
class SecioHandshake(
    private val outboundChannel: (ByteBuf) -> Unit,
    val localKey: PrivKey,
    val remotePeerId: PeerId?) {

    enum class State {
        Initial,
        ProposeSent,
        ExchangeSent,
        KeysCreated,
        SecureChannelCreated,
        FinalValidated
    }

    private var state = State.Initial

    val random: SecureRandom = SecureRandom.getInstanceStrong()
    val nonceSize = 16
    val ciphers = linkedSetOf("AES-128", "AES-256")
    val hashes = linkedSetOf("SHA256", "SHA512")
    val curves = linkedSetOf("P-256", "P-384", "P-521")

    private val nonce = ByteArray(nonceSize).apply { random.nextBytes(this) }
    private var proposeMsg: Spipe.Propose? = null;
    private var remotePropose: Spipe.Propose? = null
    private var remotePubKey: PubKey? = null
    private var order: Int? = null
    private var curve: String? = null
    private var hash: String? = null
    private var cipher: String? = null
    private var ephPrivKey: EcdsaPrivateKey? = null
    private var ephPubKey: EcdsaPublicKey? = null

    fun isComplete() = state == State.FinalValidated

    fun start() {
        val localPubKeyBytes = localKey.publicKey().bytes();

        proposeMsg = Spipe.Propose.newBuilder()
            .setRand(nonce.toProtobuf())
            .setPubkey(localPubKeyBytes.toProtobuf())
            .setExchanges(curves.joinToString(","))
            .setHashes(hashes.joinToString(","))
            .setCiphers(ciphers.joinToString(","))
            .build()

        state = State.ProposeSent
        write(proposeMsg!!)
    }

    fun onSecureChannelSetup() {
        if (state != State.KeysCreated) throw InvalidNegotiationState()
        outboundChannel.invoke(remotePropose!!.rand.toByteArray().toByteBuf())
        state = State.SecureChannelCreated
    }

    fun onNewMessage(buf: ByteBuf): Pair<SecioParams, SecioParams>? {
        when (state) {
            State.ProposeSent -> {
                remotePropose = read(buf, Spipe.Propose.parser())
                val remotePubKeyBytes = remotePropose!!.pubkey.toByteArray();
                remotePubKey = unmarshalPublicKey(remotePubKeyBytes)
                val calcedPeerId = PeerId.fromPubKey(remotePubKey!!)
                if (remotePeerId != null && calcedPeerId != remotePeerId) throw InvalidRemotePubKey()

                val h1 = sha256(remotePubKeyBytes + nonce)
                val h2 = sha256(localKey.publicKey().bytes() + remotePropose!!.rand.toByteArray())
                order = h1.compareTo(h2)
                if (order == 0) throw SelfConnecting()
                curve = selectBest(order!!, curves, remotePropose!!.exchanges.split(","))
                hash = selectBest(order!!, hashes, remotePropose!!.hashes.split(","))
                cipher = selectBest(order!!, ciphers, remotePropose!!.ciphers.split(","))

                val (ephPrivKeyL, ephPubKeyL) = generateEcdsaKeyPair(curve!!)
                ephPrivKey = ephPrivKeyL
                ephPubKey = ephPubKeyL

                val exchangeMsg = Spipe.Exchange.newBuilder()
                    .setEpubkey(ephPubKeyL.toUncompressedBytes().toProtobuf())
                    .setSignature(
                        localKey.sign(proposeMsg!!.toByteArray()
                                + remotePropose!!.toByteArray()
                                + ephPubKeyL.toUncompressedBytes()).toProtobuf()
                    ).build()

                state = State.ExchangeSent
                write(exchangeMsg)
            }
            State.ExchangeSent -> {
                val remoteExchangeMsg = read(buf, Spipe.Exchange.parser())
                if (!remotePubKey!!.verify(
                        remotePropose!!.toByteArray() + proposeMsg!!.toByteArray() + remoteExchangeMsg.epubkey.toByteArray(),
                        remoteExchangeMsg.signature.toByteArray())) {
                    throw InvalidSignature()
                }

                val ecCurve = ECNamedCurveTable.getParameterSpec(curve).curve
                val remoteEphPublickKey = decodeEcdsaPublicKeyUncompressed(curve!!, remoteExchangeMsg.epubkey.toByteArray())
                val remoteEphPubPoint = ecCurve.validatePoint(remoteEphPublickKey.pub.w.affineX, remoteEphPublickKey.pub.w.affineY)

                val sharedSecretPoint = ecCurve.multiplier.multiply(remoteEphPubPoint, ephPrivKey!!.priv.s)
                val sharedSecret = sharedSecretPoint.normalize().affineXCoord.encoded


                val (k1, k2) = stretchKeys(cipher!!, hash!!, sharedSecret)

                val localKeys = if(order!! > 0) k1 else k2
                val remoteKeys = if(order!! > 0) k2 else k1

                val hmacFactory: (ByteArray) -> HMac = { macKey ->
                    val ret = when(hash) {
                        "SHA256" -> HMac(SHA256Digest())
                        "SHA512" -> HMac(SHA512Digest())
                        else -> throw IllegalArgumentException("Unsupported hash function: $hash")
                    }
                    ret.init(KeyParameter(macKey))
                    ret
                }

                val localCipher = Cipher.getInstance("AES/CTR/NoPadding", BouncyCastleProvider())
                localCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(localKeys.cipherKey, "AES"), IvParameterSpec(localKeys.iv))
                val remoteCipher = Cipher.getInstance("AES/CTR/NoPadding", BouncyCastleProvider())
                remoteCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(remoteKeys.cipherKey, "AES"), IvParameterSpec(remoteKeys.iv))

                state = State.KeysCreated
                return Pair(
                    SecioParams(nonce, localKey.publicKey(), ephPubKey!!.bytes(),
                        localKeys, curve!!, cipher!!, hash!!, hmacFactory.invoke(localKeys.macKey), localCipher),
                    SecioParams(remotePropose!!.rand.toByteArray(), remotePubKey!!, remoteEphPubPoint.getEncoded(true),
                        remoteKeys, curve!!, cipher!!, hash!!, hmacFactory.invoke(remoteKeys.macKey), remoteCipher)
                )
            }
            State.SecureChannelCreated -> {
                if (!nonce.contentEquals(buf.toByteArray())) throw InvalidInitialPacket()
                state = State.FinalValidated
            }
            else -> throw InvalidNegotiationState()
        }
        return null
    }

    private fun write(outMsg: Message) {
        val byteBuf = Unpooled.buffer().writeBytes(outMsg.toByteArray())
        outboundChannel.invoke(byteBuf)
    }
    private fun <MessageType: Message> read(buf: ByteBuf, parser: Parser<MessageType>): MessageType {
        return parser.parseFrom(buf.nioBuffer())
    }

    private inline fun <reified T> selectBest(order: Int, p1: Collection<T>, p2: Collection<T>): T {
        val intersect = linkedSetOf(*(if (order >= 0) p1 else p2).toTypedArray())
            .intersect(linkedSetOf(*(if (order >= 0) p2 else p1).toTypedArray()))
        if (intersect.isEmpty()) throw NoCommonAlgos()
        return intersect.first()
    }
}