package io.libp2p.simulate

import io.libp2p.simulate.util.Throughput
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class ThroughputTest {

    @Test
    fun test1() {
        val timeController = TimeControllerImpl()
        val executor = ControlledExecutorServiceImpl(timeController)
        val throughput =
            Throughput(
                RandomValue.const(5000.0),
                executor,
                { (it as ByteArray).size },
                timeController::getTime
            )
        val ch1 = EmbeddedChannel(throughput.createDelayHandler())
        val ch2 = EmbeddedChannel(throughput.createDelayHandler())

        ch1.writeAndFlush(ByteArray(1))
        ch2.writeAndFlush(ByteArray(1))

        Assertions.assertNotNull(ch1.outboundMessages().poll())
        Assertions.assertNotNull(ch2.outboundMessages().poll())

        ch2.writeAndFlush(ByteArray(3))

        Assertions.assertNull(ch1.outboundMessages().poll())
        Assertions.assertNull(ch2.outboundMessages().poll())

        timeController.addTime(1)

        Assertions.assertNull(ch1.outboundMessages().poll())
        Assertions.assertNotNull(ch2.outboundMessages().poll())

        ch1.writeAndFlush(ByteArray(500))
        ch2.writeAndFlush(ByteArray(500))

        timeController.addTime(95)

        ch1.writeAndFlush(ByteArray(1))
        ch2.writeAndFlush(ByteArray(1))

        Assertions.assertNull(ch1.outboundMessages().poll())
        Assertions.assertNull(ch2.outboundMessages().poll())

        timeController.addTime(5)

        Assertions.assertNotNull(ch1.outboundMessages().poll())
        Assertions.assertNull(ch1.outboundMessages().poll())
        Assertions.assertNull(ch2.outboundMessages().poll())

        timeController.addTime(100)

        Assertions.assertNotNull(ch1.outboundMessages().poll())
        Assertions.assertNull(ch1.outboundMessages().poll())
        Assertions.assertNotNull(ch2.outboundMessages().poll())
        Assertions.assertNotNull(ch2.outboundMessages().poll())
        Assertions.assertNull(ch2.outboundMessages().poll())

        ch1.writeAndFlush(ByteArray(1))
        ch2.writeAndFlush(ByteArray(1))

        Assertions.assertNotNull(ch1.outboundMessages().poll())
        Assertions.assertNotNull(ch2.outboundMessages().poll())
    }
}
