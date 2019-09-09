package io.libp2p.core

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.etc.types.fromHex
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class PeerIdTest {

    @Test
    fun test1() {
        val idHex = "1220593cd036d6ac062ca1c332c15aca7a7b8ed7c9a004b34046e58f2aa6439102b5"
        val peerId = PeerId(idHex.fromHex())
        println("Base58: " + peerId.toBase58())
        Assertions.assertEquals("QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6", peerId.toBase58())
    }

    @Test
    fun test2() {
        val keyS =
            "080012a60230820122300d06092a864886f70d01010105000382010f003082010a0282010100baa3fd95db3f6179ce6b0f1c0c130f2fafbb3ddb20b77bac8a1a408c84af6e3de7dc09dc74cc117360ec6100fe146b7e1a298a546aa8b7b2e1de81780cc0bf888b53bf9cb5fc8145b83b34a6eb93fa41e15d5e03bb492d87f9d76b6b3b77f2d7c879cf1715ce2bde1552050f3556d42fb466e7a5eb2b9fd74f8c6dad741d4dcfde046173cb0385c498a781ea5bccb253175868384f32ac9b2579374d2e9a187acba3abb4f16a5c01c6cbfafddfb75793062e3b7a5c753e6fdfa6f7c7654466f33164680c37545a3954fd1636fdc985f6fd2237f96c949d492df0ad7686f9a72760182d3264103825e4277e1f68c03b906b3e747d5a73b6673c73890128c565170203010001"
        val pubKey = unmarshalPublicKey(keyS.fromHex())
        val fromS = "12201133e39444593a3f91c45aba4f44099fc7246866af9917f8648160180b3ec6ac"
        val peerId = PeerId.fromPubKey(pubKey)
        val peerIdExpected = PeerId(fromS.fromHex())

        Assertions.assertEquals(peerIdExpected, peerId)
    }

    @Test
    fun testSecp() {
        val (privKey, pubKey) = generateKeyPair(KEY_TYPE.SECP256K1)
        val peerId = PeerId.fromPubKey(pubKey)
        println("PeerID: " + peerId.toBase58())
        Assertions.assertEquals("16Uiu2HAmFNXiEY9pAKmiXyRxiNbMXE4E4NxFcjFHt1hHWzeP8erS", peerId.toBase58())
    }
}