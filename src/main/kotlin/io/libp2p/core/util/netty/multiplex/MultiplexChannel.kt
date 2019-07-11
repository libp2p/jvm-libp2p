package io.libp2p.core.util.netty.multiplex

import io.libp2p.core.util.netty.AbstractChildChannel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import java.net.SocketAddress

/**
 * Alternative effort to start MultistreamChannel implementation from AbstractChannel
 */
class MultiplexChannel<TData>(
    val parent: MultiplexHandler<TData>,
    val initializer: ChannelHandler,
    val id: MultiplexId
) : AbstractChildChannel(parent.ctx!!.channel(), id) {

    private var remoteDisconnected = false
    private var localDisconnected = false

    override fun metadata(): ChannelMetadata = ChannelMetadata(true)
    override fun localAddress0() =
        MultiplexSocketAddress(parent.getChannelHandlerContext().channel().localAddress(), id)

    override fun remoteAddress0() =
        MultiplexSocketAddress(parent.getChannelHandlerContext().channel().remoteAddress(), id)

    override fun doRegister() {
        super.doRegister()
        pipeline().addLast(initializer)
    }

    override fun doWrite(buf: ChannelOutboundBuffer) {
        buf.forEachFlushedMessage { parent.onChildWrite(this, it as TData) }
    }

    override fun doDisconnect() {
        localDisconnected = true
        parent.localDisconnect(this)
        deactivate()
        closeIfBothDisconnected()
    }

    fun onRemoteDisconnected() {
        pipeline().fireUserEventTriggered(RemoteWriteClosed())
        remoteDisconnected = true
        closeIfBothDisconnected()
    }

    override fun doClose() {
        super.doClose()
        parent.onClosed(this)
    }

    override fun onClientClosed() {
        parent.localClose(this)
    }

    private fun closeIfBothDisconnected() {
        if (remoteDisconnected && localDisconnected) closeImpl()
    }
}

class RemoteWriteClosed

data class MultiplexSocketAddress(val parentAddress: SocketAddress, val streamId: MultiplexId) : SocketAddress()
