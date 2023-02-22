package io.libp2p.security.tls

import crypto.pb.Crypto
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.security.SecureChannel
import io.libp2p.crypto.keys.Ed25519PublicKey
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.CombinedChannelDuplexHandler
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContextBuilder
import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.*
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import kotlin.experimental.and

private val log = Logger.getLogger(TlsSecureChannel::class.java.name)
const val MaxCipheredPacketLength = 65535
val certificatePrefix = "libp2p-tls-handshake:".encodeToByteArray()

class UShortLengthCodec : CombinedChannelDuplexHandler<LengthFieldBasedFrameDecoder, LengthFieldPrepender>(
    LengthFieldBasedFrameDecoder(MaxCipheredPacketLength + 2, 0, 2, 0, 2),
    LengthFieldPrepender(2)
)

class TlsSecureChannel(private val localKey: PrivKey) :
    SecureChannel {

    companion object {
        const val announce = "/tls/1.0.0"
    }

    override val protocolDescriptor = ProtocolDescriptor(announce)

    fun initChannel(ch: P2PChannel): CompletableFuture<SecureChannel.Session> {
        return initChannel(ch, "")
    }

    override fun initChannel(
        ch: P2PChannel,
        selectedProtocol: String
    ): CompletableFuture<SecureChannel.Session> {
        val handshakeComplete = CompletableFuture<SecureChannel.Session>()

        ch.pushHandler(UShortLengthCodec()) // Packet length codec should stay forever.

        ch.isInitiator
        val connectionKeys = generateEd25519KeyPair()
        val javaPrivateKey = getJavaKey(connectionKeys.first)
        val sslContext = SslContextBuilder.forServer(javaPrivateKey, listOf(buildCert(localKey, connectionKeys.first)))
            .protocols(listOf("TLSv1.3"))
            .clientAuth(ClientAuth.REQUIRE)
            .build()
        val handler = sslContext.newHandler(PooledByteBufAllocator.DEFAULT)
        ch.pushHandler(handler)
        val handshake = handler.handshakeFuture()
        val engine = handler.engine()
        handshake.addListener { _ ->
            handshakeComplete.complete(
                SecureChannel.Session(
                    PeerId.fromPubKey(localKey.publicKey()),
                    verifyAndExtractPeerId(engine.getSession().getPeerCertificates()),
                    getPublicKeyFromCert(engine.getSession().getPeerCertificates())
                )
            )
        }
        return handshakeComplete
    }
}

fun getJavaKey(priv: PrivKey): PrivateKey {
    if (priv.keyType == Crypto.KeyType.Ed25519) {
        val kf = KeyFactory.getInstance("Ed25519")
        val privKeyInfo =
            PrivateKeyInfo(AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519), DEROctetString(priv.raw()))
        val pkcs8KeySpec = PKCS8EncodedKeySpec(privKeyInfo.encoded)
        return kf.generatePrivate(pkcs8KeySpec)
    }
    if (priv.keyType == Crypto.KeyType.RSA) {
        throw IllegalStateException("Unimplemented RSA key support for TLS")
    }
    throw IllegalArgumentException("Unsupported TLS key type:" + priv.keyType)
}

fun getJavaPublicKey(pub: PubKey): PublicKey {
    if (pub.keyType == Crypto.KeyType.Ed25519) {
        val kf = KeyFactory.getInstance("Ed25519")

        // determine if x was odd.
        var pk = pub.raw()
        val lastbyteInt = pk[pk.lastIndex].toInt()
        var xisodd = lastbyteInt.and(255).shr(7) == 1
        // make sure most significant bit will be 0 - after reversing.
        pk[31] = pk[31].and(127);
        val y = BigInteger(1, pk.reversedArray());

        val paramSpec = NamedParameterSpec("Ed25519")
        val ep = EdECPoint(xisodd, y)
        val pubSpec = EdECPublicKeySpec(paramSpec, ep)
        return kf.generatePublic(pubSpec)
    }
    throw IllegalArgumentException("Unsupported TLS key type:" + pub.keyType)
}

fun getPubKey(pub: PublicKey): PubKey {
    if (pub.algorithm.equals("Ed25519"))
        return Ed25519PublicKey(Ed25519PublicKeyParameters(pub.encoded))
    if (pub.algorithm.equals("RSA"))
        throw IllegalStateException("Unimplemented RSA public key support for TLS")
    throw IllegalStateException("Unsupported key type: " + pub.algorithm)
}

fun verifyAndExtractPeerId(chain: Array<Certificate>): PeerId {
    if (chain.size != 1)
        throw java.lang.IllegalStateException("Cert chain must have exactly 1 element!")
    val cert = chain.get(0)
    // peerid is in the certificate extension
    val bcCert = org.bouncycastle.asn1.x509.Certificate
        .getInstance(ASN1Primitive.fromByteArray(cert.getEncoded()))
    val bcX509Cert = X509CertificateHolder(bcCert)
    val libp2pOid = ASN1ObjectIdentifier("1.3.6.1.4.1.53594.1.1")
    val extension = bcX509Cert.extensions.getExtension(libp2pOid)
    if (extension == null)
        throw IllegalStateException("Certificate extension not present!")
    val input = ASN1InputStream(extension.extnValue.encoded)
    val wrapper = input.readObject() as DEROctetString
    val seq = ASN1InputStream(wrapper.octets).readObject() as DLSequence
    val pubKeyProto = (seq.getObjectAt(0) as DEROctetString).octets
    val signature = (seq.getObjectAt(1) as DEROctetString).octets
    val pubKey = unmarshalPublicKey(pubKeyProto)
    if (! pubKey.verify(certificatePrefix.plus(cert.publicKey.encoded), signature))
        throw IllegalStateException("Invalid signature on TLS certificate extension!")

    cert.verify(cert.publicKey)
    val now = Date()
    if (bcCert.endDate.date.before(now))
        throw IllegalStateException("TLS certificate has expired!")
    if (bcCert.startDate.date.after(now))
        throw IllegalStateException("TLS certificate is not valid yet!")
    return PeerId.fromPubKey(pubKey)
}

fun getPublicKeyFromCert(chain: Array<Certificate>): PubKey {
    if (chain.size != 1)
        throw java.lang.IllegalStateException("Cert chain must have exactly 1 element!")
    val cert = chain.get(0)
    return getPubKey(cert.publicKey)
}

/** Build a self signed cert, with an extension containing the host key + sig(cert public key)
 *
 */
fun buildCert(hostKey: PrivKey, subjectKey: PrivKey) : X509Certificate {
    val publicKeyAsn1 = getJavaPublicKey(subjectKey.publicKey()).encoded
    val subPubKeyInfo = SubjectPublicKeyInfo.getInstance(publicKeyAsn1)

    val now = Instant.now()
    val validFrom = Date.from(now.minusSeconds(3600))
    val oneYear = 60L * 60 * 24 * 365
    val validTo = Date.from(now.plusSeconds(oneYear))
    val issuer = X500Name("CN=Nabu,O=Peergos,L=Oxford,C=UK")
    val subject = issuer

    val signature = hostKey.sign(certificatePrefix.plus(publicKeyAsn1))
    val hostPublicProto = hostKey.publicKey().bytes()
    val extension = DERSequence(arrayOf(DEROctetString(hostPublicProto), DEROctetString(signature)))

    var certBuilder = X509v3CertificateBuilder(
        issuer,
        BigInteger.valueOf(now.toEpochMilli()),
        validFrom,
        validTo,
        subject,
        subPubKeyInfo
    ).addExtension(ASN1ObjectIdentifier("1.3.6.1.4.1.53594.1.1"), false, extension)
    val signer = JcaContentSignerBuilder("Ed25519")
        .setProvider(BouncyCastleProvider())
        .build(getJavaKey(subjectKey))
    return JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
}
