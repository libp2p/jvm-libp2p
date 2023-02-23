package io.libp2p.security.tls

import crypto.pb.Crypto
import io.libp2p.core.*
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.security.SecureChannel
import io.libp2p.crypto.keys.Ed25519PublicKey
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.etc.REMOTE_PEER_ID
import io.libp2p.security.InvalidRemotePubKey
import io.netty.buffer.ByteBuf
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.CombinedChannelDuplexHandler
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslHandler
import io.netty.util.ReferenceCountUtil
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
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.EdECPublicKey
import java.security.spec.*
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.X509TrustManager
import kotlin.experimental.and
import kotlin.experimental.or

private val log = Logger.getLogger(TlsSecureChannel::class.java.name)
private val SetupHandlerName = "TlsSetup"
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

        ch.pushHandler(SetupHandlerName, ChannelSetup(localKey, ch.isInitiator, handshakeComplete))
        return handshakeComplete
    }
}

fun buildTlsHandler(
    localKey: PrivKey,
    expectedRemotePeer: Optional<PeerId>,
    isInitiator: Boolean,
    handshakeComplete: CompletableFuture<SecureChannel.Session>,
    ctx: ChannelHandlerContext
): SslHandler {
    val connectionKeys = generateEd25519KeyPair()
    val javaPrivateKey = getJavaKey(connectionKeys.first)
    val sslContext = (
            if (isInitiator)
                SslContextBuilder.forClient().keyManager(javaPrivateKey, listOf(buildCert(localKey, connectionKeys.first)))
            else
                SslContextBuilder.forServer(javaPrivateKey, listOf(buildCert(localKey, connectionKeys.first)))
            )
        .protocols(listOf("TLSv1.3"))
        .ciphers(listOf("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"))
        .clientAuth(ClientAuth.REQUIRE)
        .trustManager(Libp2pTrustManager(expectedRemotePeer))
        .build()
    val handler = sslContext.newHandler(PooledByteBufAllocator.DEFAULT)
    handler.sslCloseFuture().addListener { _ -> ctx.close() }
    val handshake = handler.handshakeFuture()
    val engine = handler.engine()
    handshake.addListener { fut ->
        if (! fut.isSuccess)
            handshakeComplete.completeExceptionally(fut.cause().cause)
        else
            handshakeComplete.complete(
                SecureChannel.Session(
                    PeerId.fromPubKey(localKey.publicKey()),
                    verifyAndExtractPeerId(engine.getSession().getPeerCertificates()),
                    getPublicKeyFromCert(engine.getSession().getPeerCertificates())
                )
            )
    }
    println("libp2p-tls using suites: " + sslContext.cipherSuites())
    return handler
}

private class ChannelSetup(
    private val localKey: PrivKey,
    private val isInitiator: Boolean,
    private val handshakeComplete: CompletableFuture<SecureChannel.Session>
) : SimpleChannelInboundHandler<ByteBuf>() {
    private var activated = false

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (! activated) {
            activated = true
            val expectedRemotePeerId = ctx.channel().attr(REMOTE_PEER_ID).get()
            ctx.channel().pipeline().remove(SetupHandlerName)
            ctx.channel().pipeline().addLast(buildTlsHandler(localKey, Optional.ofNullable(expectedRemotePeerId), isInitiator, handshakeComplete, ctx))
        }
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        // it seems there is no guarantee from Netty that channelActive() must be called before channelRead()
        channelActive(ctx)
        ctx.fireChannelActive()
        ReferenceCountUtil.retain(msg)
    }

    private fun writeAndFlush(ctx: ChannelHandlerContext, bb: ByteBuf) {
        ctx.writeAndFlush(bb)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        handshakeComplete.completeExceptionally(cause)
        log.log(Level.FINE, "TLS setup failed", cause)
        ctx.channel().close()
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext) {
        handshakeComplete.completeExceptionally(ConnectionClosedException("Connection was closed ${ctx.channel()}"))
        super.channelUnregistered(ctx)
    }
}

class Libp2pTrustManager(private val expectedRemotePeer: Optional<PeerId>): X509TrustManager {
    override fun checkClientTrusted(certs: Array<out X509Certificate>?, authType: String?) {
        if (certs?.size != 1)
            throw CertificateException()
        val claimedPeerId = verifyAndExtractPeerId(arrayOf(certs.get(0)))
        if (expectedRemotePeer.map { ex -> ! ex.equals(claimedPeerId) }.orElse(false))
            throw InvalidRemotePubKey()
    }

    override fun checkServerTrusted(certs: Array<out X509Certificate>?, authType: String?) {
        return checkClientTrusted(certs, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return arrayOf()
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
        pk[31] = pk[31].and(127)
        val y = BigInteger(1, pk.reversedArray())

        val paramSpec = NamedParameterSpec("Ed25519")
        val ep = EdECPoint(xisodd, y)
        val pubSpec = EdECPublicKeySpec(paramSpec, ep)
        return kf.generatePublic(pubSpec)
    }
    throw IllegalArgumentException("Unsupported TLS key type:" + pub.keyType)
}

fun getPubKey(pub: PublicKey): PubKey {
    if (pub.algorithm.equals("EdDSA") || pub.algorithm.equals("Ed25519")) {
        // It seems batshit that we have to do this, but haven't found an equivalent library call
        val point = (pub as EdECPublicKey).point
        var pk = point.y.toByteArray().reversedArray()
        if (pk.size == 31)
            pk = pk.plus(0)
        if (point.isXOdd)
            pk[31] = pk[31].or(0x80.toByte())
        return Ed25519PublicKey(Ed25519PublicKeyParameters(pk))
    }
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
fun buildCert(hostKey: PrivKey, subjectKey: PrivKey): X509Certificate {
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
