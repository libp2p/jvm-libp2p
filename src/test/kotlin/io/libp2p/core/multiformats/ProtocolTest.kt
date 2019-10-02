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
    fun test1() {
        assertEquals(Protocol.TCP, Protocol.get("tcp"))
        assertEquals(Protocol.TCP, Protocol.get(6))
        assertEquals("12345", Protocol.TCP.bytesToAddress(Protocol.TCP.addressToBytes("12345")))
        for (protocol in Protocol.values()) {
            assertEquals(if (protocol == Protocol.IPFS) Protocol.P2P else protocol,
                Protocol.getOrThrow(protocol.encoded.toByteBuf().readUvarint().toInt()))
        }
    }
}