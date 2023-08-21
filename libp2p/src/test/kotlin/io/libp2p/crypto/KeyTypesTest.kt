package io.libp2p.crypto

import io.libp2p.crypto.keys.generateEcdsaKeyPair
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.crypto.keys.generateRsaKeyPair
import io.libp2p.crypto.keys.generateSecp256k1KeyPair
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyTypesTest {

    @Test
    fun ed25519() {
        val pair = generateEd25519KeyPair()
        val toSign = "G'day!".toByteArray()
        val signed = pair.first.sign(toSign)
        assertTrue(pair.second.verify(toSign, signed))
    }

    @Test
    fun rsa() {
        val pair = generateRsaKeyPair(2048)
        val toSign = "G'day!".toByteArray()
        val signed = pair.first.sign(toSign)
        assertTrue(pair.second.verify(toSign, signed))
    }

    @Test
    fun secp256k1() {
        val pair = generateSecp256k1KeyPair()
        val toSign = "G'day!".toByteArray()
        val signed = pair.first.sign(toSign)
        assertTrue(pair.second.verify(toSign, signed))
    }

    @Test
    fun ecdsa() {
        val pair = generateEcdsaKeyPair() // p-256
        val toSign = "G'day!".toByteArray()
        val signed = pair.first.sign(toSign)
        assertTrue(pair.second.verify(toSign, signed))
    }
}
