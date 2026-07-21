package io.libp2p.pubsub

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PubsubOutboundLimitsTest {
    @Test
    fun `usage adds nonnegative resources`() {
        assertThat(OutboundResourceUsage.ZERO.plusOrNull(OutboundResourceUsage(7, 3)))
            .isEqualTo(OutboundResourceUsage(7, 3))
    }

    @Test
    fun `usage rejects negative additions`() {
        assertThat(OutboundResourceUsage.ZERO.plusOrNull(OutboundResourceUsage(-1, 0))).isNull()
        assertThat(OutboundResourceUsage.ZERO.plusOrNull(OutboundResourceUsage(0, -1))).isNull()
    }

    @Test
    fun `usage rejects arithmetic overflow`() {
        assertThat(
            OutboundResourceUsage(Long.MAX_VALUE, 0)
                .plusOrNull(OutboundResourceUsage(1, 0))
        ).isNull()
        assertThat(
            OutboundResourceUsage(0, Long.MAX_VALUE)
                .plusOrNull(OutboundResourceUsage(0, 1))
        ).isNull()
    }

    @Test
    fun `usage fits inclusive limits`() {
        val limits = PubsubOutboundLimits(10, 2)

        assertThat(OutboundResourceUsage(10, 2).fits(limits)).isTrue()
        assertThat(OutboundResourceUsage(11, 2).fits(limits)).isFalse()
        assertThat(OutboundResourceUsage(10, 3).fits(limits)).isFalse()
    }
}
