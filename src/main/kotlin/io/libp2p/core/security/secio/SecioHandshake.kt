package io.libp2p.core.security.secio

import com.google.protobuf.Message
import com.google.protobuf.Parser
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.StretchedKey
import io.libp2p.core.crypto.keys.decodeEcdsaPublicKeyUncompressed
import io.libp2p.core.crypto.keys.generateEcdsaKeyPair
import io.libp2p.core.crypto.sha256
import io.libp2p.core.crypto.stretchKeys
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.types.compareTo
import io.libp2p.core.types.toProtobuf
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kotlinx.coroutines.channels.ReceiveChannel
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
    private val inboundChannel: ReceiveChannel<ByteBuf>,
    private val outboundChannel: suspend (ByteBuf) -> Unit,
    val localKey: PrivKey,
    val remotePeerId: PeerId?) {

    val random: SecureRandom = SecureRandom.getInstanceStrong()
    val nonceSize = 16
    val ciphers = linkedSetOf("AES-128", "AES-256")
    val hashes = linkedSetOf("SHA256", "SHA512")
    val curves = linkedSetOf("P-256", "P-384", "P-521")

    suspend fun doHandshake(): Pair<SecioParams, SecioParams> {
        val nonce = ByteArray(nonceSize)
        random.nextBytes(nonce)
        val localPubKeyBytes = localKey.publicKey().bytes();

        val proposeMsg = Spipe.Propose.newBuilder()
            .setRand(nonce.toProtobuf())
            .setPubkey(localPubKeyBytes.toProtobuf())
            .setExchanges(curves.joinToString(","))
            .setHashes(hashes.joinToString(","))
            .setCiphers(ciphers.joinToString(","))
            .build()

        val remotePropose: Spipe.Propose = writeRead(proposeMsg)

        val remotePubKeyBytes = remotePropose.pubkey.toByteArray();
        val remotePubKey = unmarshalPublicKey(remotePubKeyBytes)
        val calcedPeerId = PeerId.fromPubKey(remotePubKey)
        if (remotePeerId != null && calcedPeerId != remotePeerId) throw InvalidRemotePubKey()

        val h1 = sha256(remotePubKeyBytes + nonce)
        val h2 = sha256(localPubKeyBytes + remotePropose.rand.toByteArray())
        val order = h1.compareTo(h2);
        val curve = selectBest(order, curves, remotePropose.exchanges.split(","))
        val hash = selectBest(order, hashes, remotePropose.hashes.split(","))
        val cipher = selectBest(order, ciphers, remotePropose.ciphers.split(","))

        val (ephPrivKey, ephPubKey) = generateEcdsaKeyPair(curve)

        val exchangeMsg = Spipe.Exchange.newBuilder()
            .setEpubkey(ephPubKey.toUncompressedBytes().toProtobuf())
            .setSignature(
                localKey.sign(proposeMsg.toByteArray()
                        + remotePropose.toByteArray()
                        + ephPubKey.toUncompressedBytes()).toProtobuf()
            ).build()

        val remoteExchangeMsg = writeRead(exchangeMsg)

        if (!remotePubKey.verify(
                remotePropose.toByteArray() + proposeMsg.toByteArray() + remoteExchangeMsg.epubkey.toByteArray(),
                remoteExchangeMsg.signature.toByteArray())) {
            throw InvalidSignature()
        }

        val ecCurve = ECNamedCurveTable.getParameterSpec(curve).curve
        val remoteEphPublickKey = decodeEcdsaPublicKeyUncompressed(curve, remoteExchangeMsg.epubkey.toByteArray())
        val remoteEphPubPoint = ecCurve.validatePoint(remoteEphPublickKey.pub.w.affineX, remoteEphPublickKey.pub.w.affineY)

        val sharedSecretPoint = ecCurve.multiplier.multiply(remoteEphPubPoint, ephPrivKey.priv.s)
        val sharedSecret = sharedSecretPoint.normalize().affineXCoord.encoded


        val (k1, k2) = stretchKeys(cipher, hash, sharedSecret)

        val localKeys = if(order > 0) k1 else k2
        val remoteKeys = if(order > 0) k2 else k1

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

        return Pair(
            SecioParams(nonce, localKey.publicKey(), ephPubKey.bytes(),
                localKeys, curve, cipher, hash, hmacFactory.invoke(localKeys.macKey), localCipher),
            SecioParams(remotePropose.rand.toByteArray(), remotePubKey, remoteEphPubPoint.getEncoded(true),
                remoteKeys, curve, cipher, hash, hmacFactory.invoke(remoteKeys.macKey), remoteCipher)
        )
    }

    private suspend inline fun <MessageType: Message> writeRead(outMsg: MessageType): MessageType {
        val byteBuf = Unpooled.buffer().writeBytes(outMsg.toByteArray())
        outboundChannel.invoke(byteBuf)
        val buf = inboundChannel.receive()
        val parser: Parser<MessageType> = outMsg.parserForType as Parser<MessageType>
        return parser.parseFrom(buf.nioBuffer())
    }

    private inline fun <reified T> selectBest(order: Int, p1: Collection<T>, p2: Collection<T>): T {
        val intersect = linkedSetOf(*(if (order >= 0) p1 else p2).toTypedArray())
            .intersect(linkedSetOf(*(if (order >= 0) p2 else p1).toTypedArray()))
        if (intersect.isEmpty()) throw NoCommonAlgos()
        return intersect.first()
    }
}