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
package io.libp2p.crypto

import io.libp2p.etc.types.toHex
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

/**
 * ErrRsaKeyTooSmall is returned when trying to generate or parse an RSA key
 * that's smaller than 512 bits. Keys need to be larger enough to sign a 256bit
 * hash so this is a reasonable absolute minimum.
 */
const val ErrRsaKeyTooSmall = "rsa keys must be >= 512 bits to be useful"

const val RSA_ALGORITHM = "RSA"
const val SHA_ALGORITHM = "SHA-256"

const val ECDSA_ALGORITHM = "ECDSA"

const val ED25519_ALGORITHM = "ED25519"

const val SECP_256K1_ALGORITHM = "secp256k1"

const val P256_CURVE = "P-256"

const val SHA_256_WITH_RSA = "SHA256withRSA"
const val SHA_256_WITH_ECDSA = "SHA256withECDSA"

const val KEY_PKCS8 = "PKCS#8"

object Libp2pCrypto {

    val provider = org.bouncycastle.jce.provider.BouncyCastleProvider()
}

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

    val hmac = when (hashType) {
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
