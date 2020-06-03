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
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * @param priv the private key backing this instance.
 */
class Ed25519PrivateKey(private val priv: Ed25519PrivateKeyParameters) : PrivKey(Crypto.KeyType.Ed25519) {

    override fun raw(): ByteArray = priv.encoded

    override fun sign(data: ByteArray): ByteArray = with(Ed25519Signer()) {
        init(true, priv)
        update(data, 0, data.size)
        generateSignature()
    }

    override fun publicKey(): PubKey = Ed25519PublicKey(priv.generatePublicKey())

    override fun hashCode(): Int = priv.hashCode()
}

/**
 * @param pub the public key backing this instance.
 */
class Ed25519PublicKey(private val pub: Ed25519PublicKeyParameters) : PubKey(Crypto.KeyType.Ed25519) {

    override fun raw(): ByteArray = pub.encoded

    override fun verify(data: ByteArray, signature: ByteArray): Boolean = with(Ed25519Signer()) {
        init(false, pub)
        update(data, 0, data.size)
        verifySignature(signature)
    }

    override fun hashCode(): Int = pub.hashCode()
}

/**
 * @return a newly-generated ED25519 private and public key pair.
 */
@JvmOverloads
fun generateEd25519KeyPair(random: SecureRandom = SecureRandom()): Pair<PrivKey, PubKey> = with(Ed25519KeyPairGenerator()) {
    init(Ed25519KeyGenerationParameters(random))
    val keypair = generateKeyPair()
    val privateKey = keypair.private as Ed25519PrivateKeyParameters
    Pair(
        Ed25519PrivateKey(privateKey),
        Ed25519PublicKey(keypair.public as Ed25519PublicKeyParameters)
    )
}

/**
 * Unmarshals the given key bytes into an ED25519 private key instance.
 * @param keyBytes the key bytes.
 * @return a private key.
 */
fun unmarshalEd25519PrivateKey(keyBytes: ByteArray): PrivKey =
    Ed25519PrivateKey(Ed25519PrivateKeyParameters(keyBytes, 0))

/**
 * Unmarshals the given key bytes into an ED25519 public key instance.
 * @param keyBytes the key bytes.
 * @return a public key.
 */
fun unmarshalEd25519PublicKey(keyBytes: ByteArray): PubKey =
    Ed25519PublicKey(Ed25519PublicKeyParameters(keyBytes, 0))
