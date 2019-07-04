package io.libp2p.core.crypto

import io.libp2p.core.crypto.keys.unmarshalEcdsaPublicKey
import io.libp2p.core.types.fromHex
import org.junit.jupiter.api.Test


/**
 * Created by Anton Nashatyrev on 21.06.2019.
 */
class KeyTest {

    @Test
    fun testDecodeECPubKey() {
        val pubKeyBytes =
            "04df83a2880f74cbd22df27934f0a68f44786d2c7e6633046dac07edaeab3d3549cd98a3d98fd22325bcae93b66056534a8835a0fa66391b7903c7995ff428280d".fromHex()
        val publicKey = unmarshalEcdsaPublicKey(pubKeyBytes)
        println(publicKey)
    }
}