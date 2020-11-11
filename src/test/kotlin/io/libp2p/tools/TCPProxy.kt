package io.libp2p.tools

import io.libp2p.etc.util.netty.nettyInitializer
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

// Utility class (aka sniffer) that just forwards TCP traffic back'n'forth to another TCP address and log it
class TCPProxy {

    fun start(listenPort: Int, dialHost: String, dialPort: Int): ChannelFuture {
        val future = ServerBootstrap().apply {
            group(NioEventLoopGroup())
            channel(NioServerSocketChannel::class.java)
            childHandler(
                nettyInitializer {
                    it.addLastLocal(object : ChannelInboundHandlerAdapter() {
                        val client = CompletableFuture<ChannelHandlerContext>()
                        override fun channelActive(serverCtx: ChannelHandlerContext) {

                            serverCtx.channel().pipeline().addFirst(LoggingHandler("server", LogLevel.INFO))

                            Bootstrap().apply {
                                group(NioEventLoopGroup())
                                channel(NioSocketChannel::class.java)
                                option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5 * 1000)
                                handler(object : ChannelInboundHandlerAdapter() {
                                    override fun channelRead(ctx: ChannelHandlerContext?, msg: Any?) {
                                        serverCtx.writeAndFlush(msg)
                                    }

                                    override fun channelActive(ctx: ChannelHandlerContext) {
//                                serverCtx.channel().pipeline().addFirst(LoggingHandler("client", LogLevel.INFO))
                                        client.complete(ctx)
                                    }

                                    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
                                        client.get().close()
                                    }
                                })
                            }.connect(dialHost, dialPort).await().channel()
                        }

                        override fun channelRead(ctx: ChannelHandlerContext?, msg: Any?) {
                            client.get().writeAndFlush(msg)
                        }

                        override fun channelUnregistered(ctx: ChannelHandlerContext?) {
                            client.get().close()
                        }
                    })
                }
            )
        }.bind(listenPort).sync()
        println("Proxying TCP traffic from port $listenPort to $dialHost:$dialPort...")
        return future
    }

    @Test
    @Disabled
    fun run() {
        start(11111, "localhost", 10000)
            .channel().closeFuture().await()
    }
}
