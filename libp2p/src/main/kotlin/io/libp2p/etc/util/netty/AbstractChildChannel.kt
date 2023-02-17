package io.libp2p.etc.util.netty

import io.netty.channel.AbstractChannel
import io.netty.channel.Channel
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelId
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelConfig
import io.netty.channel.EventLoop
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import java.net.SocketAddress

/**
 * Class representing 'virtual' channel which has a parent and
 * is closed automatically on parent close
 * Since this type of channels has no underlying transport connect() and bind() methods
 * are not supported
 */
abstract class AbstractChildChannel(parent: Channel, id: ChannelId?) : AbstractChannel(parent, id) {
    private enum class State {
        OPEN, ACTIVE, INACTIVE, CLOSED
    }

    private val parentCloseFuture = parent.closeFuture()
    private var state = State.OPEN
    private var closeImplicitly = false
    private val parentCloseListener = GenericFutureListener { _: Future<Void> -> closeImpl() }

    fun closeImpl() {
        closeImplicitly = true
        try {
            close()
        } finally {
            closeImplicitly = false
        }
    }

    override fun metadata(): ChannelMetadata = ChannelMetadata(false)
    override fun config(): ChannelConfig = DefaultChannelConfig(this)
    override fun isCompatible(loop: EventLoop?) = true

    override fun isOpen(): Boolean {
        return state != State.CLOSED
    }

    override fun isActive(): Boolean {
        return state == State.ACTIVE
    }

    override fun doRegister() {
        state = State.ACTIVE
        parentCloseFuture.addListener(parentCloseListener)
    }

    override fun doDeregister() {
        // NOOP
    }

    override fun doDisconnect() {
        if (!metadata().hasDisconnect()) {
            doClose()
        }
    }

    override fun doClose() {
        if (!closeImplicitly) onClientClosed()
        deactivate()
        pipeline().deregister()
        parentCloseFuture.removeListener(parentCloseListener)
        state = State.CLOSED
    }

    protected open fun onClientClosed() {}

    protected fun deactivate() {
        if (state == State.ACTIVE) {
            pipeline().fireChannelInactive()
            state = State.INACTIVE
        }
    }

    override fun doBeginRead() {
        // NOOP
    }

    override fun doBind(localAddress: SocketAddress?) {
        throw UnsupportedOperationException("ChildChannel doesn't support bind()")
    }

    override fun newUnsafe(): AbstractUnsafe = MUnsafe()

    private inner class MUnsafe : AbstractUnsafe() {
        override fun connect(remoteAddress: SocketAddress?, localAddress: SocketAddress?, promise: ChannelPromise?) {
            throw UnsupportedOperationException("ChildChannel doesn't support connect()")
        }
    }
}
