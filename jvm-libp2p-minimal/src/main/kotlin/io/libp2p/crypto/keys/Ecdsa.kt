/*
 * Copyright 2019 BLK Technologies Limited (web3labs.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.libp2p.crypto.keys

import crypto.pb.Crypto
import io.libp2p.core.Libp2pException
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.crypto.ECDSA_ALGORITHM
import io.libp2p.crypto.KEY_PKCS8
import io.libp2p.crypto.Libp2pCrypto
import io.libp2p.crypto.P256_CURVE
import io.libp2p.crypto.SHA_256_WITH_ECDSA
import io.libp2p.etc.types.toBytes
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.ECPointUtil
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec
import org.bouncycastle.jce.spec.ECNamedCurveSpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.interfaces.ECPrivateKey as JavaECPrivateKey
import java.security.interfaces.ECPublicKey as JavaECPublicKey

private val CURVE: ECNamedCurveParameterSpec = ECNamedCurveTable.getParameterSpec(P256_CURVE)

/**
 * @param priv the private key backing this instance.
 */
class EcdsaPrivateKey(val priv: JavaECPrivateKey) : PrivKey(Crypto.KeyType.ECDSA) {

    init {
        // Set up private key.
        if (priv.format != KEY_PKCS8) {
            throw Libp2pException("Private key must be of '$KEY_PKCS8' format")
        }
    }

    override fun raw(): ByteArray = priv.encoded

    /**
     * Sign the given bytes and returns the signature of the input data.
     * @param data the bytes to be signed.
     * @return the signature as a byte array.
     */
    override fun sign(data: ByteArray): ByteArray =
        with(Signature.getInstance(SHA_256_WITH_ECDSA, Libp2pCrypto.provider)) {
            // Signature is made up of r and s numbers.
            initSign(priv)
            update(data)
            sign()
        }

    override fun publicKey(): EcdsaPublicKey {
        val pubSpec: ECPublicKeySpec = (priv as BCECPrivateKey).run {
            val q = parameters.g.multiply((this as org.bouncycastle.jce.interfaces.ECPrivateKey).d)
            ECPublicKeySpec(q, parameters)
        }

        return with(KeyFactory.getInstance(ECDSA_ALGORITHM, Libp2pCrypto.provider)) {
            EcdsaPublicKey(generatePublic(pubSpec) as JavaECPublicKey)
        }
    }

    override fun hashCode(): Int = priv.hashCode()
}

/**
 * @param pub the public key backing this instance.
 */
class EcdsaPublicKey(val pub: JavaECPublicKey) : PubKey(Crypto.KeyType.ECDSA) {

    override fun raw(): ByteArray = pub.encoded

    fun toUncompressedBytes(): ByteArray =
        byteArrayOf(0x04) + pub.w.affineX.toBytes(32) + pub.w.affineY.toBytes(32)

    override fun verify(data: ByteArray, signature: ByteArray): Boolean =
        with(Signature.getInstance(SHA_256_WITH_ECDSA, Libp2pCrypto.provider)) {
            initVerify(pub)
            update(data)
            verify(signature)
        }

    override fun hashCode(): Int = pub.hashCode()
}

/**
 * Generates a new ECDSA private and public key with a specified curve.
 * @param curve the curve spec.
 * @return a pair of private and public keys.
 */
private fun generateECDSAKeyPairWithCurve(curve: ECNamedCurveParameterSpec, random: SecureRandom = SecureRandom()): Pair<EcdsaPrivateKey, EcdsaPublicKey> {
    val keypair: KeyPair = with(KeyPairGenerator.getInstance(ECDSA_ALGORITHM, Libp2pCrypto.provider)) {
        initialize(curve, random)
        genKeyPair()
    }

    return Pair(
        EcdsaPrivateKey(keypair.private as JavaECPrivateKey),
        EcdsaPublicKey(keypair.public as JavaECPublicKey)
    )
}

/**
 * Generates a new ECDSA private and public key pair.
 * @return a pair of private and public keys.
 */
@JvmOverloads
fun generateEcdsaKeyPair(random: SecureRandom = SecureRandom()): Pair<PrivKey, PubKey> {
    // http://www.bouncycastle.org/wiki/display/JA1/Supported+Curves+%28ECDSA+and+ECGOST%29
    // and
    // http://www.bouncycastle.org/wiki/pages/viewpage.action?pageId=362269
    return generateECDSAKeyPairWithCurve(CURVE, random)
}

fun generateEcdsaKeyPair(curve: String): Pair<EcdsaPrivateKey, EcdsaPublicKey> {
    // http://www.bouncycastle.org/wiki/display/JA1/Supported+Curves+%28ECDSA+and+ECGOST%29
    // and
    // http://www.bouncycastle.org/wiki/pages/viewpage.action?pageId=362269
    return generateECDSAKeyPairWithCurve(ECNamedCurveTable.getParameterSpec(curve))
}

/**
 * Generates a new ecdsa private and public key from an input private key.
 * @param priv the private key.
 * @return a pair of private and public keys.
 */
fun ecdsaKeyPairFromKey(priv: EcdsaPrivateKey): Pair<PrivKey, PubKey> = Pair(priv, priv.publicKey())

/**
 * Unmarshals the given key bytes into an ECDSA private key instance.
 * @param keyBytes the key bytes.
 * @return a private key.
 */
fun unmarshalEcdsaPrivateKey(keyBytes: ByteArray): PrivKey = EcdsaPrivateKey(
    KeyFactory.getInstance(ECDSA_ALGORITHM, Libp2pCrypto.provider).generatePrivate(
        PKCS8EncodedKeySpec(keyBytes)
    ) as JavaECPrivateKey
)

/**
 * Unmarshals the given key bytes into an ECDSA public key instance.
 * @param keyBytes the key bytes.
 * @return a public key.
 */
fun unmarshalEcdsaPublicKey(keyBytes: ByteArray): EcdsaPublicKey =
    with(KeyFactory.getInstance(ECDSA_ALGORITHM, Libp2pCrypto.provider)) {
        EcdsaPublicKey(generatePublic(X509EncodedKeySpec(keyBytes)) as JavaECPublicKey)
    }

fun decodeEcdsaPublicKeyUncompressed(ecCurve: String, keyBytes: ByteArray): EcdsaPublicKey {
    val spec = ECNamedCurveTable.getParameterSpec(ecCurve)
    val kf = KeyFactory.getInstance("ECDSA", BouncyCastleProvider())
    val params = ECNamedCurveSpec(ecCurve, spec.getCurve(), spec.getG(), spec.getN())
    val point = ECPointUtil.decodePoint(params.getCurve(), keyBytes)
    val pubKeySpec = java.security.spec.ECPublicKeySpec(point, params)
    val publicKey = kf.generatePublic(pubKeySpec)
    publicKey as JavaECPublicKey
    return EcdsaPublicKey(publicKey)
}
