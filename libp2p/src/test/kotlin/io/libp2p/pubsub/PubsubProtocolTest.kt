package io.libp2p.pubsub

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PubsubProtocolTest {

    @Test
    fun `supportsBackoffAndPX is true for all GossipSub versions from v1_1 onwards`() {
        assertThat(PubsubProtocol.Gossip_V_1_0.supportsBackoffAndPX()).isFalse()
        assertThat(PubsubProtocol.Gossip_V_1_1.supportsBackoffAndPX()).isTrue()
        assertThat(PubsubProtocol.Gossip_V_1_2.supportsBackoffAndPX()).isTrue()
        assertThat(PubsubProtocol.Gossip_V_1_3.supportsBackoffAndPX()).isTrue()
        assertThat(PubsubProtocol.Floodsub.supportsBackoffAndPX()).isFalse()
    }

    @Test
    fun `supportsIDontWant is true for all GossipSub versions from v1_2 onwards`() {
        assertThat(PubsubProtocol.Gossip_V_1_0.supportsIDontWant()).isFalse()
        assertThat(PubsubProtocol.Gossip_V_1_1.supportsIDontWant()).isFalse()
        assertThat(PubsubProtocol.Gossip_V_1_2.supportsIDontWant()).isTrue()
        assertThat(PubsubProtocol.Gossip_V_1_3.supportsIDontWant()).isTrue()
        assertThat(PubsubProtocol.Floodsub.supportsIDontWant()).isFalse()
    }

    @Test
    fun `supportsExtensions is true only for GossipSub v1_3`() {
        assertThat(PubsubProtocol.Gossip_V_1_0.supportsExtensions()).isFalse()
        assertThat(PubsubProtocol.Gossip_V_1_1.supportsExtensions()).isFalse()
        assertThat(PubsubProtocol.Gossip_V_1_2.supportsExtensions()).isFalse()
        assertThat(PubsubProtocol.Gossip_V_1_3.supportsExtensions()).isTrue()
        assertThat(PubsubProtocol.Floodsub.supportsExtensions()).isFalse()
    }
}
