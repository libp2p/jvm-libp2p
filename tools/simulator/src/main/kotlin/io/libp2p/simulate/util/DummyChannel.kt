package io.libp2p.simulate.util

import io.netty.buffer.ByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelId
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelProgressivePromise
import io.netty.channel.ChannelPromise
import io.netty.channel.EventLoop
import io.netty.util.DefaultAttributeMap
import java.net.SocketAddress

class DummyChannel : DefaultAttributeMap(), Channel {

    override fun writeAndFlush(msg: Any?, promise: ChannelPromise?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun writeAndFlush(msg: Any?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun isActive(): Boolean {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun alloc(): ByteBufAllocator {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun id(): ChannelId {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun newPromise(): ChannelPromise {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun write(msg: Any?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun write(msg: Any?, promise: ChannelPromise?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun closeFuture(): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun flush(): Channel {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun connect(remoteAddress: SocketAddress?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun connect(remoteAddress: SocketAddress?, localAddress: SocketAddress?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun connect(remoteAddress: SocketAddress?, promise: ChannelPromise?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun connect(
        remoteAddress: SocketAddress?,
        localAddress: SocketAddress?,
        promise: ChannelPromise?
    ): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun newFailedFuture(cause: Throwable?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun remoteAddress(): SocketAddress {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun config(): ChannelConfig {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun newSucceededFuture(): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun isOpen(): Boolean {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun bytesBeforeUnwritable(): Long {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun bytesBeforeWritable(): Long {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun pipeline(): ChannelPipeline {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun close(): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun close(promise: ChannelPromise?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun read(): Channel {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun voidPromise(): ChannelPromise {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun parent(): Channel {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun deregister(): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun deregister(promise: ChannelPromise?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun compareTo(other: Channel?): Int {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun disconnect(): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun disconnect(promise: ChannelPromise?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun unsafe(): Channel.Unsafe {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun newProgressivePromise(): ChannelProgressivePromise {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun isWritable(): Boolean {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun metadata(): ChannelMetadata {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun localAddress(): SocketAddress {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun bind(localAddress: SocketAddress?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun bind(localAddress: SocketAddress?, promise: ChannelPromise?): ChannelFuture {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun isRegistered(): Boolean {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun eventLoop(): EventLoop {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }
}
