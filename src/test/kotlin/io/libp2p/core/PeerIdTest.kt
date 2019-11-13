package io.libp2p.core

import io.libp2p.core.crypto.unmarshalPrivateKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.crypto.keys.secp256k1PublicKeyFromCoordinates
import io.libp2p.etc.types.fromHex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigInteger

class PeerIdTest {
    @Test
    fun `from hex id`() {
        val idHex = "1220593cd036d6ac062ca1c332c15aca7a7b8ed7c9a004b34046e58f2aa6439102b5"
        val peerId = PeerId(idHex.fromHex())
        assertEquals("QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6", peerId.toBase58())
    }

    @Test
    fun `from RSA public key hex`() {
        val keyS =
            "080012a60230820122300d06092a864886f70d01010105000382010f003082010a0282010100baa3fd95db3f6179ce6b0f1c0c130f2fafbb3ddb20b77bac8a1a408c84af6e3de7dc09dc74cc117360ec6100fe146b7e1a298a546aa8b7b2e1de81780cc0bf888b53bf9cb5fc8145b83b34a6eb93fa41e15d5e03bb492d87f9d76b6b3b77f2d7c879cf1715ce2bde1552050f3556d42fb466e7a5eb2b9fd74f8c6dad741d4dcfde046173cb0385c498a781ea5bccb253175868384f32ac9b2579374d2e9a187acba3abb4f16a5c01c6cbfafddfb75793062e3b7a5c753e6fdfa6f7c7654466f33164680c37545a3954fd1636fdc985f6fd2237f96c949d492df0ad7686f9a72760182d3264103825e4277e1f68c03b906b3e747d5a73b6673c73890128c565170203010001"
        val pubKey = unmarshalPublicKey(keyS.fromHex())
        val peerId = PeerId.fromPubKey(pubKey)

        val fromS = "12201133e39444593a3f91c45aba4f44099fc7246866af9917f8648160180b3ec6ac"
        val peerIdExpected = PeerId(fromS.fromHex())

        assertEquals(peerIdExpected, peerId)
    }

    @Test
    fun `from Secp256k public key hex`() {
        val pubKey =
            unmarshalPublicKey("08021221030995d3b6ca88154681092a6772b26de6418eaa01672775bfe9642b33d1f97227".fromHex())
        val peerId = PeerId.fromPubKey(pubKey)
        assertEquals("16Uiu2HAmDJQXZM39z5TzoZN7Sw8tbwjR6Evg9CFmiWhLbnSFGADL", peerId.toBase58())
    }

    @Test
    fun `from Secp256k public key coordinates`() {
        val pubKey = secp256k1PublicKeyFromCoordinates(
            BigInteger("29868384252041196073668708557917617583643409287860779134116445706780092854384"),
            BigInteger("32121030900263138255369578555559933217781286061089165917390620197021766129989")
        )
        val peerId = PeerId.fromPubKey(pubKey)
        assertEquals("16Uiu2HAmH6m8pE7pXsfzXHg3Z5kLzgwJ5PWSYELaKXc75Bs2utZM", peerId.toBase58())
    }

    @Test
    fun `from Secp256k private key hex`() {
        val serializedGoKey = """
            08 02 12 20 6F D7 AF 84 82 36 61 AE 4F 0F DB 05
            D8 3B D5 56 9B 42 70 FE 94 C8 AD 79 CC B7 E3 F8
            43 DE FC B1
        """.trimIndent()
            .replace(" ", "")
            .replace("\n", "")

        val privKey = unmarshalPrivateKey(serializedGoKey.fromHex())
        val peerId = PeerId.fromPubKey(privKey.publicKey())
        assertEquals("16Uiu2HAmH6m8pE7pXsfzXHg3Z5kLzgwJ5PWSYELaKXc75Bs2utZM", peerId.toBase58())
    }
}