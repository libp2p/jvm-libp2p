package io.libp2p.simulate.delay

import io.libp2p.simulate.Bandwidth
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.milliseconds

class ChannelMessageDelayerTest {
    val timeController = TimeControllerImpl()
    val executor = ControlledExecutorServiceImpl(timeController)

    private val Int.bytesPerSecond get() = Bandwidth(this.toLong())
    private fun Bandwidth.simpleDelayer() = SimpleBandwidthTracker(this, executor)
    private fun Int.millisLatencyDelayer() = TimeDelayer(executor) { this.milliseconds }

    @Test
    fun `slow outbound bandwidth prevails`() {
        val delayer = ChannelMessageDelayer(
            executor,
            1000.bytesPerSecond.simpleDelayer(),
            300.millisLatencyDelayer(),
            1000000.bytesPerSecond.simpleDelayer()
        )

        val delay = delayer.delay(1000).thenApply { timeController.time }

        timeController.addTime(2000)
        assertThat(delay).isCompletedWithValue(1300)
    }

    @Test
    fun `slow inbound bandwidth prevails`() {
        val delayer = ChannelMessageDelayer(
            executor,
            1000000.bytesPerSecond.simpleDelayer(),
            300.millisLatencyDelayer(),
            1000.bytesPerSecond.simpleDelayer()
        )

        val delay = delayer.delay(1000).thenApply { timeController.time }
        timeController.addTime(2000)

        assertThat(delay).isCompletedWithValue(1300)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `subsequent messages ordered and timely`(outboundBandwidthSlower: Boolean) {
        val delayer =
            when (outboundBandwidthSlower) {
                true -> ChannelMessageDelayer(
                    executor,
                    1000.bytesPerSecond.simpleDelayer(),
                    300.millisLatencyDelayer(),
                    1000000.bytesPerSecond.simpleDelayer()
                )

                false -> ChannelMessageDelayer(
                    executor,
                    1000000.bytesPerSecond.simpleDelayer(),
                    300.millisLatencyDelayer(),
                    1000.bytesPerSecond.simpleDelayer()
                )
            }

        val delay1 = delayer.delay(1000).thenApply { timeController.time }
        val delay2 = delayer.delay(10).thenApply { timeController.time }
        timeController.addTime(200)
        val delay3 = delayer.delay(10).thenApply { timeController.time }
        timeController.addTime(1099)
        val delay4 = delayer.delay(10).thenApply { timeController.time }
        timeController.addTime(10000)

        assertThat(delay1).isCompletedWithValue(1300)
        assertThat(delay2).isCompletedWithValue(1310)
        assertThat(delay3).isCompletedWithValue(1320)
        assertThat(delay4).isCompletedWithValue(1609)
    }
}
