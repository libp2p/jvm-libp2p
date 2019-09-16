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

import com.southernstorm.noise.protocol.DHState
import com.southernstorm.noise.protocol.Noise
import crypto.pb.Crypto
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey

/**
 * @param priv the private key backing this instance.
 */
class Curve25519PrivateKey(private val state: DHState) : PrivKey(Crypto.KeyType.Curve25519) {

    override fun raw(): ByteArray {
        val ba = ByteArray(state.privateKeyLength)
        state.getPrivateKey(ba, 0)
        return ba
    }

    override fun sign(data: ByteArray): ByteArray {
        throw NotImplementedError("Signing with Curve25519 private key currently unsupported.")
    }

    override fun publicKey(): PubKey {
        val ba = ByteArray(state.publicKeyLength)
        state.getPublicKey(ba, 0)
        return Curve25519PublicKey(state)
    }

    override fun hashCode(): Int = raw().contentHashCode()
}

/**
 * @param pub the public key backing this instance.
 */
class Curve25519PublicKey(private val state: DHState) : PubKey(Crypto.KeyType.Curve25519) {

    override fun raw(): ByteArray {
        val ba = ByteArray(state.publicKeyLength)
        state.getPublicKey(ba, 0)
        return ba
    }

    override fun verify(data: ByteArray, signature: ByteArray): Boolean {
        throw NotImplementedError("Verifying with Curve25519 public key currently unsupported.")
    }

    override fun hashCode(): Int = raw().contentHashCode()
}

/**
 * @return a newly-generated Curve25519 private and public key pair.
 */
fun generateCurve25519KeyPair(): Pair<PrivKey, PubKey> {
    val k = Noise.createDH("25519")
    k.generateKeyPair()

    val prk = Curve25519PrivateKey(k)
    val puk = Curve25519PublicKey(k)
    return Pair(prk, puk)
}

/**
 * Unmarshals the given key bytes into an Curve25519 private key instance.
 * @param keyBytes the key bytes.
 * @return a private key.
 */
fun unmarshalCurve25519PrivateKey(keyBytes: ByteArray): PrivKey {
    val dh = Noise.createDH("25519")
    dh.setPrivateKey(keyBytes, 0)
    return Curve25519PrivateKey(dh)
}

/**
 * Unmarshals the given key bytes into an Curve25519 public key instance.
 * @param keyBytes the key bytes.
 * @return a public key.
 */
fun unmarshalCurve25519PublicKey(keyBytes: ByteArray): PubKey {
    val dh = Noise.createDH("25519")
    dh.setPrivateKey(keyBytes, 0)
    return Curve25519PublicKey(dh)
}
