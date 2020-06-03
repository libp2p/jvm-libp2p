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
import io.libp2p.core.crypto.sha256
import io.libp2p.crypto.SECP_256K1_ALGORITHM
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERSequenceGenerator
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import org.bouncycastle.math.ec.FixedPointUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom

// The parameters of the secp256k1 curve that Bitcoin uses.
private val CURVE_PARAMS = CustomNamedCurves.getByName(SECP_256K1_ALGORITHM)

private val CURVE: ECDomainParameters = CURVE_PARAMS.let {
    FixedPointUtil.precompute(CURVE_PARAMS.g)
    ECDomainParameters(CURVE_PARAMS.curve, CURVE_PARAMS.g, CURVE_PARAMS.n, CURVE_PARAMS.h)
}

private val S_UPPER_BOUND = BigInteger("7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF5D576E7357A4501DDFE92F46681B20A0", 16)
private val S_FIXER_VALUE = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

/**
 * @param privateKey the private key backing this instance.
 */
class Secp256k1PrivateKey(private val privateKey: ECPrivateKeyParameters) : PrivKey(Crypto.KeyType.Secp256k1) {

    private val priv = privateKey.d

    override fun raw(): ByteArray = priv.toByteArray()

    override fun sign(data: ByteArray): ByteArray {
        val (r, s) = with(ECDSASigner()) {
            init(true, ParametersWithRandom(privateKey, SecureRandom()))
            generateSignature(sha256(data)).let {
                Pair(it[0], it[1])
            }
        }

        val s_ = if (s <= S_UPPER_BOUND) s else S_FIXER_VALUE - s

        return with(ByteArrayOutputStream()) {
            DERSequenceGenerator(this).run {
                addObject(ASN1Integer(r))
                addObject(ASN1Integer(s_))
                close()
                toByteArray()
            }
        }
    }

    override fun publicKey(): PubKey {
        val privKey = if (priv.bitLength() > CURVE.n.bitLength()) priv.mod(CURVE.n) else priv
        val publicPoint = FixedPointCombMultiplier().multiply(CURVE.g, privKey)
        return Secp256k1PublicKey(ECPublicKeyParameters(publicPoint, CURVE))
    }

    override fun hashCode(): Int = priv.hashCode()
}

/**
 * @param pub the public key backing this instance.
 */
class Secp256k1PublicKey(private val pub: ECPublicKeyParameters) : PubKey(Crypto.KeyType.Secp256k1) {

    override fun raw(): ByteArray = pub.q.getEncoded(true)

    override fun verify(data: ByteArray, signature: ByteArray): Boolean {
        val signer = ECDSASigner().also {
            it.init(false, pub)
        }

        val asn1: ASN1Primitive =
            ByteArrayInputStream(signature)
                .use { inStream ->
                    ASN1InputStream(inStream)
                        .use { asnInputStream ->
                            asnInputStream.readObject()
                        }
                }

        val asn1Encodables = (asn1 as ASN1Sequence).toArray().also {
            if (it.size != 2) {
                throw Libp2pException("Invalid signature: expected 2 values for 'r' and 's' but got ${it.size}")
            }
        }

        val r = (asn1Encodables[0].toASN1Primitive() as ASN1Integer).value
        val s = (asn1Encodables[1].toASN1Primitive() as ASN1Integer).value
        return signer.verifySignature(sha256(data), r.abs(), s.abs())
    }

    override fun hashCode(): Int = pub.hashCode()
}

/**
 * Generates a new SECP256K1 private and public key.
 * @return a pair of the private and public keys.
 */
@JvmOverloads
fun generateSecp256k1KeyPair(random: SecureRandom = SecureRandom()): Pair<PrivKey, PubKey> = with(ECKeyPairGenerator()) {
    val domain = SECNamedCurves.getByName(SECP_256K1_ALGORITHM).let {
        ECDomainParameters(it.curve, it.g, it.n, it.h)
    }
    init(ECKeyGenerationParameters(domain, random))
    val keypair = generateKeyPair()

    val privateKey = keypair.private as ECPrivateKeyParameters
    return Pair(
        Secp256k1PrivateKey(privateKey),
        Secp256k1PublicKey(keypair.public as ECPublicKeyParameters)
    )
}

/**
 * Unmarshals the given key bytes into a SECP256K1 private key instance.
 * @param keyBytes the key bytes.
 * @return a private key instance.
 */
fun unmarshalSecp256k1PrivateKey(data: ByteArray): PrivKey =
    Secp256k1PrivateKey(ECPrivateKeyParameters(BigInteger(1, data), CURVE))

/**
 * Unmarshals the given key bytes into a SECP256K1 public key instance.
 * @param keyBytes the key bytes.
 * @return a public key instance.
 */
fun unmarshalSecp256k1PublicKey(data: ByteArray): PubKey =
    Secp256k1PublicKey(
        ECPublicKeyParameters(
            CURVE.curve.decodePoint(data),
            CURVE
        )
    )

fun secp256k1PublicKeyFromCoordinates(x: BigInteger, y: BigInteger): PubKey =
    Secp256k1PublicKey(
        ECPublicKeyParameters(
            CURVE.curve.createPoint(x, y),
            CURVE
        )
    )
