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
package io.libp2p.core.crypto

import com.google.protobuf.ByteString
import crypto.pb.Crypto
import io.libp2p.core.crypto.keys.generateEcdsaKeyPair
import io.libp2p.core.crypto.keys.generateEd25519KeyPair
import io.libp2p.core.crypto.keys.generateRsaKeyPair
import io.libp2p.core.crypto.keys.generateSecp256k1KeyPair
import io.libp2p.core.crypto.keys.unmarshalEcdsaPrivateKey
import io.libp2p.core.crypto.keys.unmarshalEcdsaPublicKey
import io.libp2p.core.crypto.keys.unmarshalEd25519PrivateKey
import io.libp2p.core.crypto.keys.unmarshalEd25519PublicKey
import io.libp2p.core.crypto.keys.unmarshalRsaPrivateKey
import io.libp2p.core.crypto.keys.unmarshalRsaPublicKey
import io.libp2p.core.crypto.keys.unmarshalSecp256k1PrivateKey
import io.libp2p.core.crypto.keys.unmarshalSecp256k1PublicKey
import io.libp2p.core.types.toHex
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import crypto.pb.Crypto.PrivateKey as PbPrivateKey
import crypto.pb.Crypto.PublicKey as PbPublicKey

enum class KEY_TYPE {

    /**
     * RSA is an enum for the supported RSA key type
     */
    RSA,

    /**
     * Ed25519 is an enum for the supported Ed25519 key type
     */
    ED25519,

    /**
     * Secp256k1 is an enum for the supported Secp256k1 key type
     */
    SECP256K1,

    /**
     * ECDSA is an enum for the supported ECDSA key type
     */
    ECDSA
}

interface Key {

    val keyType: crypto.pb.Crypto.KeyType

    /**
     * Bytes returns a serialized, storeable representation of this key.
     */
    fun bytes(): ByteArray

    fun raw(): ByteArray
}

/**
 * PrivKey represents a private key that can be used to generate a public key,
 * sign data, and decrypt data that was encrypted with a public key.
 */
abstract class PrivKey(override val keyType: Crypto.KeyType) : Key {

    /**
     * Cryptographically sign the given bytes.
     */
    abstract fun sign(data: ByteArray): ByteArray

    /**
     * Return a public key paired with this private key.
     */
    abstract fun publicKey(): PubKey

    override fun bytes(): ByteArray = marshalPrivateKey(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return bytes().contentEquals((other as PrivKey).bytes())
    }
}

/**
 * PubKey is a public key.
 */
abstract class PubKey(override val keyType: Crypto.KeyType) : Key {

    /**
     * Verify that 'sig' is the signed hash of 'data'.
     */
    abstract fun verify(data: ByteArray, signature: ByteArray): Boolean

    override fun bytes(): ByteArray = marshalPublicKey(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return bytes().contentEquals((other as PubKey).bytes())
    }
}

class BadKeyTypeException : Exception("Invalid or unsupported key type")

/**
 * Generates a new key pair of the provided type.
 * @param type the type key to be generated.
 * @param bits the number of bits desired for the key (only applicable for RSA).
 */
fun generateKeyPair(type: KEY_TYPE, bits: Int = 0): Pair<PrivKey, PubKey> {

    return when (type) {
        KEY_TYPE.RSA -> generateRsaKeyPair(bits)
        KEY_TYPE.ED25519 -> generateEd25519KeyPair()
        KEY_TYPE.SECP256K1 -> generateSecp256k1KeyPair()
        KEY_TYPE.ECDSA -> generateEcdsaKeyPair()
    }
}

/**
 * Converts the protobuf serialized public key into its representative object.
 * @param data the byte array of the protobuf public key.
 * @return the equivalent public key.
 */
fun unmarshalPublicKey(data: ByteArray): PubKey {
    val pmes = PbPublicKey.parseFrom(data)

    val pmesd = pmes.data.toByteArray()

    return when (pmes.type) {
        Crypto.KeyType.RSA -> unmarshalRsaPublicKey(pmesd)
        Crypto.KeyType.Ed25519 -> unmarshalEd25519PublicKey(pmesd)
        Crypto.KeyType.Secp256k1 -> unmarshalSecp256k1PublicKey(pmesd)
        Crypto.KeyType.ECDSA -> unmarshalEcdsaPublicKey(pmesd)
        else -> throw BadKeyTypeException()
    }
}

/**
 * Converts a public key object into a protobuf serialized public key.
 * @param pubKey the public key instance.
 * @return the protobuf bytes.
 */
fun marshalPublicKey(pubKey: PubKey): ByteArray =
    PbPublicKey.newBuilder()
        .setType(pubKey.keyType)
        .setData(ByteString.copyFrom(pubKey.raw()))
        .build()
        .toByteArray()

/**
 * Converts a protobuf serialized private key into its representative object.
 * @param data the byte array of hte protobuf private key.
 * @return the equivalent private key.
 */
fun unmarshalPrivateKey(data: ByteArray): PrivKey {
    val pmes = PbPrivateKey.parseFrom(data)

    val pmesd = pmes.data.toByteArray()

    return when (pmes.type) {
        Crypto.KeyType.RSA -> unmarshalRsaPrivateKey(pmesd)
        Crypto.KeyType.Ed25519 -> unmarshalEd25519PrivateKey(pmesd)
        Crypto.KeyType.Secp256k1 -> unmarshalSecp256k1PrivateKey(pmesd)
        Crypto.KeyType.ECDSA -> unmarshalEcdsaPrivateKey(pmesd)
        else -> throw BadKeyTypeException()
    }
}

/**
 * Converts a public key object into a protobuf serialized private key.
 * @param privKey the private key.
 * @return the protobuf bytes.
 */
fun marshalPrivateKey(privKey: PrivKey): ByteArray =
    PbPrivateKey.newBuilder()
        .setType(privKey.keyType)
        .setData(ByteString.copyFrom(privKey.raw()))
        .build()
        .toByteArray()

data class StretchedKey(val iv: ByteArray, val cipherKey: ByteArray, val macKey: ByteArray) {
    override fun toString(): String =
        "StretchedKey[iv=" + iv.toHex() + ", cipherKey=" + cipherKey.toHex() + ", macKey=" + macKey.toHex() + "]"
}

fun stretchKeys(cipherType: String, hashType: String, secret: ByteArray): Pair<StretchedKey, StretchedKey> {
    val ivSize = 16
    val cipherKeySize = when (cipherType) {
        "AES-128" -> 16
        "AES-256" -> 32
        else -> throw IllegalArgumentException("Unsupported cipher: $cipherType")
    }
    val hmacKeySize = 20
    val seed = "key expansion".toByteArray()
    val result = ByteArray(2 * (ivSize + cipherKeySize + hmacKeySize))

    val hmac = when(hashType) {
        "SHA256" -> HMac(SHA256Digest())
        "SHA512" -> HMac(SHA512Digest())
        else -> throw IllegalArgumentException("Unsupported hash function: $hashType")
    }
    hmac.init(KeyParameter(secret))

    hmac.update(seed, 0, seed.size)
    val a = ByteArray(hmac.macSize)
    hmac.doFinal(a, 0)

    var j = 0
    while (j < result.size) {
        hmac.reset()
        hmac.update(a, 0, a.size)
        hmac.update(seed, 0, seed.size)

        val b = ByteArray(hmac.macSize)
        hmac.doFinal(b, 0)

        var todo = b.size

        if (j + todo > result.size) {
            todo = result.size - j
        }

        b.copyInto(result, j, 0, todo)
        j += todo

        hmac.reset()
        hmac.update(a, 0, a.size)
        hmac.doFinal(a, 0)
    }

    val half = result.size / 2
    val r1 = result.sliceArray(0 until half)
    val r2 = result.sliceArray(half until result.size)

    return Pair(
        StretchedKey(
            r1.sliceArray(0 until ivSize),
            r1.sliceArray(ivSize until ivSize + cipherKeySize),
            r1.sliceArray(ivSize + cipherKeySize until r1.size)
        ),
        StretchedKey(
            r2.sliceArray(0 until ivSize),
            r2.sliceArray(ivSize until ivSize + cipherKeySize),
            r2.sliceArray(ivSize + cipherKeySize until r2.size)
        )
    )
}