package io.libp2p.transport.quic

import io.libp2p.core.PeerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QuicHolePunchIdentityTest {

    @Test
    fun `accepts the inbound peer when its identity matches the dial target`() {
        val target = PeerId.random()

        assertThat(holePunchIdentityMatches(expected = target, actual = target)).isTrue()
    }

    @Test
    fun `rejects the inbound peer when its identity differs from the dial target`() {
        val target = PeerId.random()
        val impostor = PeerId.random()

        assertThat(holePunchIdentityMatches(expected = target, actual = impostor)).isFalse()
    }

    @Test
    fun `accepts any identity when the dial target carried no peer id`() {
        assertThat(holePunchIdentityMatches(expected = null, actual = PeerId.random())).isTrue()
    }
}
