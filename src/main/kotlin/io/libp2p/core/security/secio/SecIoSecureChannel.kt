package io.libp2p.core.security.secio

import io.libp2p.core.protocol.Mode
import io.libp2p.core.protocol.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel

class SecIoSecureChannel : SecureChannel {
    override val matcher = ProtocolMatcher(Mode.STRICT, name = "/secio/1.0.0")

    override fun initializer(): ChannelInitializer<SocketChannel> =
        object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(ch: SocketChannel) {
                ch.pipeline().addLast(SecIoHandler())
            }
        }

    class SecIoHandler: ChannelInboundHandlerAdapter()

}