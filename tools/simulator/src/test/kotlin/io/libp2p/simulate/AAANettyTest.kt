package io.libp2p.simulate

import io.libp2p.etc.util.netty.LoggingHandlerShort
import io.libp2p.tools.log
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.Test

class AAANettyTest {

    @Test
    fun test1() {
        log("Connecting...")
        val channelFuture = Bootstrap()
            .group(NioEventLoopGroup())
            .channel(NioSocketChannel::class.java)
            .handler(LoggingHandlerShort(LogLevel.ERROR))
            .connect("172.104.245.33", 7777)
            .sync()
        val channel = channelFuture.await().channel()
        log("Got channel")
//        channel.pipeline().addLast(LoggingHandler())

        val writePromise = channel.newPromise()

        val msgSize = 1 * 1024 * 1024
        val msg = Unpooled.wrappedBuffer(ByteArray(msgSize))

        log("Writing message...")
        writePromise.addListener {
            log("writePromise complete")
        }
        val writeChannelFuture = channel.writeAndFlush(msg, writePromise)
        writeChannelFuture.addListener {
            log("writeChannelFuture complete")
        }
        log("write() called. Waiting...")

        Thread.sleep(1000000000)
    }
}
