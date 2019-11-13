package io.libp2p.core.multiformats

import io.libp2p.etc.types.readUvarint
import io.libp2p.etc.types.toByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Created by Anton Nashatyrev on 11.06.2019.
 */
class ProtocolTest {
    @Test
    fun protocolEncodingRoundTripping() {
        for (p in Protocol.values()) {
            val protocol = if (p == Protocol.IPFS) Protocol.P2P else p
            // IPFS and P2P are equivalent

            val roundTrippedId = p.encoded.toByteBuf().readUvarint().toInt()
            val roundTrippedProtocol = Protocol.getOrThrow(roundTrippedId)

            assertEquals(protocol, roundTrippedProtocol)
        }
    }
    @Test
    fun tcpProtocolProperties() {
        assertEquals(Protocol.TCP, Protocol.get("tcp"))
        assertEquals(Protocol.TCP, Protocol.get(6))
        assertEquals("12345", Protocol.TCP.bytesToAddress(Protocol.TCP.addressToBytes("12345")))
    }
}