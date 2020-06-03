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
import io.libp2p.crypto.ErrRsaKeyTooSmall
import io.libp2p.crypto.KEY_PKCS8
import io.libp2p.crypto.Libp2pCrypto
import io.libp2p.crypto.RSA_ALGORITHM
import io.libp2p.crypto.SHA_256_WITH_RSA
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.PrivateKey as JavaPrivateKey
import java.security.PublicKey as JavaPublicKey

/**
 * @param sk the private key backing this instance.
 * @param pk the public key backing this instance.
 */
class RsaPrivateKey(private val sk: JavaPrivateKey, private val pk: JavaPublicKey) : PrivKey(Crypto.KeyType.RSA) {

    private val rsaPublicKey = RsaPublicKey(pk)
    private val pkcs1PrivateKeyBytes: ByteArray

    init {
        // Set up private key.
        val isKeyOfFormat: Boolean = sk.format?.equals(KEY_PKCS8) ?: false
        if (!isKeyOfFormat) {
            throw Libp2pException("Private key must be of '$KEY_PKCS8' format")
        }

        val bcPrivateKeyInfo = PrivateKeyInfo.getInstance(sk.encoded)
        pkcs1PrivateKeyBytes = bcPrivateKeyInfo.parsePrivateKey().toASN1Primitive().encoded
    }

    override fun raw(): ByteArray = pkcs1PrivateKeyBytes

    override fun sign(data: ByteArray): ByteArray =
        with(Signature.getInstance(SHA_256_WITH_RSA, Libp2pCrypto.provider)) {
            initSign(sk)
            update(data)
            sign()
        }

    override fun publicKey(): PubKey = rsaPublicKey

    override fun hashCode(): Int = pk.hashCode()
}

/**
 * @param k the public key backing this instance.
 */
class RsaPublicKey(private val k: JavaPublicKey) : PubKey(Crypto.KeyType.RSA) {

    override fun raw(): ByteArray = k.encoded

    override fun verify(data: ByteArray, signature: ByteArray): Boolean =
        with(Signature.getInstance(SHA_256_WITH_RSA, Libp2pCrypto.provider)) {
            initVerify(k)
            update(data)
            verify(signature)
        }

    override fun hashCode(): Int = k.hashCode()
}

/**
 * Generates a new rsa private and public key.
 * @param bits the number of bits required in the key.
 * @return a pair of the private and public keys.
 */
@JvmOverloads
fun generateRsaKeyPair(bits: Int, random: SecureRandom = SecureRandom()): Pair<PrivKey, PubKey> {
    if (bits < 2048) {
        throw Libp2pException(ErrRsaKeyTooSmall)
    }

    val kp: KeyPair = with(
        KeyPairGenerator.getInstance(
            RSA_ALGORITHM,
            Libp2pCrypto.provider
        )
    ) {
        initialize(bits, random)
        genKeyPair()
    }

    return Pair(
        RsaPrivateKey(kp.private, kp.public),
        RsaPublicKey(kp.public)
    )
}

/**
 * Unmarshals the given key bytes into an RSA public key instance.
 * @param keyBytes the key bytes.
 * @return a private key.
 */
fun unmarshalRsaPublicKey(keyBytes: ByteArray): PubKey =
    RsaPublicKey(
        KeyFactory.getInstance(
            RSA_ALGORITHM,
            Libp2pCrypto.provider
        ).generatePublic(X509EncodedKeySpec(keyBytes))
    )

/**
 * Unmarshals the given key bytes (in PKCS1 format) into an RSA PKCS8 private key instance.
 * @param keyBytes the key bytes.
 * @return a private key instance.
 */
fun unmarshalRsaPrivateKey(keyBytes: ByteArray): PrivKey {
    // Input is ASN1 DER encoded PKCS1 private key bytes.
    val rsaPrivateKey = RSAPrivateKey.getInstance(ASN1Primitive.fromByteArray(keyBytes))
    val privateKeyParameters = RSAPrivateCrtKeyParameters(
        rsaPrivateKey.modulus,
        rsaPrivateKey.publicExponent,
        rsaPrivateKey.privateExponent,
        rsaPrivateKey.prime1,
        rsaPrivateKey.prime2,
        rsaPrivateKey.exponent1,
        rsaPrivateKey.exponent2,
        rsaPrivateKey.coefficient
    )

    // Now convert to a PKSC#8 key.
    val privateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(privateKeyParameters)
    val algorithmId = privateKeyInfo.privateKeyAlgorithm.algorithm.id
    val spec = PKCS8EncodedKeySpec(privateKeyInfo.encoded)
    val sk = KeyFactory.getInstance(algorithmId, Libp2pCrypto.provider).generatePrivate(spec)

    // We can extract the public key from the modulus and exponent of the private key. Woot!
    val publicKeySpec = RSAPublicKeySpec(privateKeyParameters.modulus, privateKeyParameters.publicExponent)
    val keyFactory = KeyFactory.getInstance(RSA_ALGORITHM)
    val pk = keyFactory.generatePublic(publicKeySpec)

    return RsaPrivateKey(sk, pk)
}
