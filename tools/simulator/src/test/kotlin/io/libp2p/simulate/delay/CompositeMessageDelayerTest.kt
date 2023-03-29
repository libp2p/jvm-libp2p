package io.libp2p.simulate.delay

import io.libp2p.etc.types.minutes
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.delay.bandwidth.AccurateBandwidthTracker
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class CompositeMessageDelayerTest {
    val timeController = TimeControllerImpl()
    val executor = ControlledExecutorServiceImpl(timeController)


    val outBandwidth = AccurateBandwidthTracker(10.mbitsPerSecond, executor, { timeController.time })
    val inBandwidth = AccurateBandwidthTracker(10.mbitsPerSecond, executor, { timeController.time })
    val latency = TimeDelayer(executor) { 100.milliseconds }
    val delayer =
        CompositeMessageDelayer(outBandwidth, latency, inBandwidth, executor, { timeController.time })

    @Test
    fun `test that latency doesnt affect sequential message delays`() {
        val msg1 = delayer
            .delay(10 * 1024)
            .thenApply { timeController.time to it }
        val msg2 = delayer
            .delay(10 * 1024)
            .thenApply { timeController.time to it }

        timeController.addTime(1.minutes)

        println(msg1.get())
        println(msg2.get())

        assertThat(msg1.get().first).isCloseTo(110, offset(2))
        assertThat(msg2.get().first).isCloseTo(120, offset(2))
    }

    @Test
    fun `test messages qre sequential`() {
        val latencyIt = iterator {
            yield(100.milliseconds)
            yield(10.milliseconds)
        }
        val latency = TimeDelayer(executor) {
            latencyIt.next()
        }
        val delayer =
            CompositeMessageDelayer(outBandwidth, latency, inBandwidth, executor, { timeController.time })

        val msg1 = delayer
            .delay(10 * 1024)
            .thenApply { timeController.time to it }
        val msg2 = delayer
            .delay(10 * 1024)
            .thenApply { timeController.time to it }

        timeController.addTime(1.minutes)

        println(msg1.get())
        println(msg2.get())

        assertThat(msg1.get().second.latencyDelay).isEqualTo(100)
        assertThat(msg2.get().second.latencyDelay).isEqualTo(10)
        assertThat(msg1.get().first).isLessThanOrEqualTo(msg2.get().first)
    }
}