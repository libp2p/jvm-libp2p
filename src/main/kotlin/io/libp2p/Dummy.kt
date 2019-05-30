package io.libp2p

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel

const val PORT = 8090

class EchoInboundHandler : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
        val bb = msg as ByteBuf
        with(ctx) {
            write(bb)
            flush()
        }
    }
}

fun main() {
    val bossGroup = NioEventLoopGroup()
    val workerGroup = NioEventLoopGroup()

    try {
        val b = ServerBootstrap()
        b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel?) {
                    ch?.pipeline()?.addLast(EchoInboundHandler())
                }
            })
            .childOption(ChannelOption.SO_KEEPALIVE, true)

        with(b.bind(PORT).sync()) {
            channel().closeFuture().sync()
        }
    } finally {
        bossGroup.shutdownGracefully()
        workerGroup.shutdownGracefully()
    }
}
