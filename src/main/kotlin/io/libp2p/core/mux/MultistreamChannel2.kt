package io.libp2p.core.mux

import io.netty.buffer.ByteBuf
import io.netty.channel.AbstractChannel
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelConfig
import io.netty.channel.EventLoop
import java.net.SocketAddress

/**
 * Alternative effort to start MultistreamChannel implementation from AbstractChannel
 */
class MultistreamChannel2(
    val parent: MultistreamHandler,
    val id: MultistreamId
) : AbstractChannel(parent.ctx!!.channel(), id) {

    val localAddress = MultistreamSocketAddress()
    val remoteAddress = MultistreamSocketAddress()

    override fun metadata(): ChannelMetadata = ChannelMetadata(true)
    override fun config(): ChannelConfig = DefaultChannelConfig(this)
    override fun isCompatible(loop: EventLoop?) = true
    override fun localAddress0() = localAddress
    override fun remoteAddress0() = remoteAddress

    override fun isActive(): Boolean {
        return true
    }

    override fun isOpen(): Boolean {
        return true
    }

    fun doConnect(remoteAddress: SocketAddress?, localAddress: SocketAddress?, promise: ChannelPromise?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun doBind(localAddress: SocketAddress?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun doRegister() {
        pipeline().addLast(parent.createInboundStreamHandler(id))
    }

    override fun doBeginRead() {

    }

    override fun doWrite(buf: ChannelOutboundBuffer) {
        buf.forEachFlushedMessage { parent.onChildWrite(this, it as ByteBuf) }
    }

    override fun doDisconnect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun doClose() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun doDeregister() {
        super.doDeregister()
    }


    override fun newUnsafe(): AbstractUnsafe = MUnsafe()

    private inner class MUnsafe: AbstractUnsafe() {
        override fun connect(remoteAddress: SocketAddress?, localAddress: SocketAddress?, promise: ChannelPromise?) {
            doConnect(remoteAddress, localAddress, promise)
        }
    }
}

class MultistreamSocketAddress: SocketAddress() {

}