package io.libp2p.etc.util.netty.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.etc.util.netty.AbstractChildChannel
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import io.netty.util.ReferenceCountUtil
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
    private var disconnectFlushedMessages = 0
    private var retainedPendingWrite: Any? = null

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

    @Suppress("SwallowedException")
    override fun doWrite(buf: ChannelOutboundBuffer) {
        while (true) {
            val msg = buf.current() ?: break
            if (localDisconnected && disconnectFlushedMessages == 0) {
                // Must not throw from doWrite — exceptions escape uncaught to the Netty event loop.
                // Wrap buf.remove() defensively: in some Netty versions promise listeners triggered
                // by buf.remove() can propagate back through it.
                try {
                    buf.remove(ConnectionClosedException("The stream was closed for writing locally: $id"))
                } catch (e: Throwable) { }
                releaseRetainedPendingWrite(msg)
                continue
            }
            if (!parent.canWriteChild(this)) {
                retainPendingWrite(msg)
                break
            }
            try {
                @Suppress("UNCHECKED_CAST")
                val consumedBytes = parent.onChildWrite(this, msg as TData)
                if (consumedBytes <= 0) {
                    retainPendingWrite(msg)
                    break
                }
                val currentMessageComplete = msg !is ByteBuf || consumedBytes >= msg.readableBytes()
                if (!currentMessageComplete) {
                    retainPendingWrite(msg)
                }
                if (msg is ByteBuf) {
                    buf.removeBytes(consumedBytes.toLong())
                } else {
                    buf.remove()
                }
                parent.flushChildWrites()
                if (currentMessageComplete) {
                    releaseRetainedPendingWrite(msg)
                }
                if (localDisconnected && currentMessageComplete) {
                    disconnectFlushedMessages--
                    if (disconnectFlushedMessages == 0) {
                        finishLocalDisconnect()
                        continue
                    }
                }
            } catch (cause: Throwable) {
                buf.remove(cause)
                releaseRetainedPendingWrite(msg)
            }
        }
    }

    override fun doDisconnect() {
        localDisconnected = true
        disconnectFlushedMessages = unsafe().outboundBuffer()?.size() ?: 0
        if (disconnectFlushedMessages == 0) {
            finishLocalDisconnect()
        }
    }

    fun onRemoteDisconnected() {
        pipeline().fireUserEventTriggered(RemoteWriteClosed)
        remoteDisconnected = true
        closeIfBothDisconnected()
    }

    override fun doClose() {
        retainedPendingWrite?.let {
            retainedPendingWrite = null
            ReferenceCountUtil.release(it)
        }
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

    private fun retainPendingWrite(msg: Any) {
        if (retainedPendingWrite !== msg) {
            retainedPendingWrite = msg
            ReferenceCountUtil.retain(msg)
        }
    }

    private fun releaseRetainedPendingWrite(msg: Any) {
        if (retainedPendingWrite === msg) {
            retainedPendingWrite = null
            ReferenceCountUtil.release(msg)
        }
    }
}

data class MultiplexSocketAddress(val parentAddress: SocketAddress, val streamId: MuxId) : SocketAddress() {
    override fun toString(): String {
        return "Mux[$parentAddress-$streamId]"
    }
}
