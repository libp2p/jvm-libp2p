package io.libp2p.etc.util.netty.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.etc.util.netty.AbstractChildChannel
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import java.net.SocketAddress

/**
 * Alternative effort to start MultistreamChannel implementation from AbstractChannel
 */
class MuxChannel<TData>(
    private val parent: AbstractMuxHandler<TData>,
    val id: MuxId,
    private val initializer: MuxChannelInitializer<TData>,
    val initiator: Boolean
) : AbstractChildChannel(parent.ctx!!.channel(), id) {

    var remoteDisconnected = false
    var localDisconnected = false
    private var localDisconnectSent = false
    private var pendingDisconnectWrites = 0

    override fun metadata(): ChannelMetadata = ChannelMetadata(true)
    override fun localAddress0() =
        MultiplexSocketAddress(parent.getChannelHandlerContext().channel().localAddress(), id)

    override fun remoteAddress0() =
        MultiplexSocketAddress(parent.getChannelHandlerContext().channel().remoteAddress(), id)

    override fun doRegister() {
        super.doRegister()
        initializer(this)
    }

    fun retryWrite() {
        if (isActive) {
            flush()
        }
    }

    fun setMuxWritability(index: Int, writable: Boolean) {
        unsafe().outboundBuffer()?.setUserDefinedWritability(index, writable)
    }

    fun pendingWriteBytes(): Long = unsafe().outboundBuffer()?.totalPendingWriteBytes() ?: 0L

    @Suppress("SwallowedException")
    override fun doWrite(buf: ChannelOutboundBuffer) {
        while (true) {
            val msg = buf.current() ?: break
            if (localDisconnected && pendingDisconnectWrites == 0) {
                // Must not throw from doWrite — exceptions escape uncaught to the Netty event loop.
                // Wrap buf.remove() defensively: in some Netty versions promise listeners triggered
                // by buf.remove() can propagate back through it.
                try {
                    buf.remove(ConnectionClosedException("The stream was closed for writing locally: $id"))
                } catch (e: Throwable) { }
                continue
            }
            try {
                @Suppress("UNCHECKED_CAST")
                msg as TData
                val complete: Boolean
                if (msg is ByteBuf && msg.isReadable) {
                    val consumedBytes = parent.onChildWrite(this, msg)
                    if (consumedBytes <= 0) {
                        // the muxer can't write at the moment: the message stays buffered
                        // (the outbound buffer owns it) until the parent retries this write
                        break
                    }
                    complete = consumedBytes >= msg.readableBytes()
                    buf.removeBytes(consumedBytes.toLong())
                } else {
                    parent.onChildWrite(this, msg)
                    complete = true
                    buf.remove()
                }
                parent.flushChildWrites()
                if (!complete) {
                    // partially written: wait for a retry before sending the rest
                    break
                }
                onPendingDisconnectWriteCompleted()
            } catch (cause: Throwable) {
                buf.remove(cause)
                onPendingDisconnectWriteCompleted()
            }
        }
    }

    override fun doDisconnect() {
        localDisconnected = true
        // delay the local disconnect until all writes flushed before the disconnect are sent
        pendingDisconnectWrites = unsafe().outboundBuffer()?.size() ?: 0
        if (pendingDisconnectWrites == 0) {
            finishLocalDisconnect()
        }
    }

    private fun onPendingDisconnectWriteCompleted() {
        if (localDisconnected && pendingDisconnectWrites > 0) {
            pendingDisconnectWrites--
            if (pendingDisconnectWrites == 0) {
                finishLocalDisconnect()
            }
        }
    }

    fun onRemoteDisconnected() {
        pipeline().fireUserEventTriggered(RemoteWriteClosed)
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
        if (remoteDisconnected && localDisconnectSent) closeImpl()
    }

    private fun finishLocalDisconnect() {
        if (localDisconnectSent) {
            return
        }
        localDisconnectSent = true
        parent.localDisconnect(this)
        deactivate()
        closeIfBothDisconnected()
    }
}

data class MultiplexSocketAddress(val parentAddress: SocketAddress, val streamId: MuxId) : SocketAddress() {
    override fun toString(): String {
        return "Mux[$parentAddress-$streamId]"
    }
}
