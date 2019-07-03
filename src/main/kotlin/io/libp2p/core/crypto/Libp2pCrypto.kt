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
