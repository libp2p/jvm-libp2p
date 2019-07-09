package io.libp2p.core.mux

import io.libp2p.core.Libp2pException
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelId
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelProgressivePromise
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelConfig
import io.netty.channel.DefaultChannelPipeline
import io.netty.channel.EventLoop
import io.netty.channel.MessageSizeEstimator
import io.netty.channel.RecvByteBufAllocator
import io.netty.channel.VoidChannelPromise
import io.netty.channel.WriteBufferWaterMark
import io.netty.handler.codec.http2.Http2DataFrame
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2Exception
import io.netty.handler.codec.http2.Http2Frame
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.Http2StreamFrame
import io.netty.util.DefaultAttributeMap
import io.netty.util.ReferenceCountUtil
import io.netty.util.internal.StringUtil
import java.io.IOException
import java.net.SocketAddress
import java.nio.channels.ClosedChannelException
import java.util.*
import java.util.concurrent.RejectedExecutionException

/**
 * Draft effort to port io.netty.handler.codec.http2.Http2MultiplexCodec
 */
class MultistreamChannel(
    val outbound: Boolean,
    val parentChannelContext: ChannelHandlerContext,
    val inboundStreamHandler: ChannelHandler
                        ): DefaultAttributeMap(), Channel {

    private val metadata = ChannelMetadata(false, 16)
    private val config = MultistreamChannelConfig(this)
    private val unsafe = ChannelUnsafe()
    private val channelId = MultistreamId(1L)
    private val pipeline = MultistreamChannelPipeline(this)
//    private val stream: Http2FrameCodec.DefaultHttp2FrameStream? = null
    private val closePromise: ChannelPromise = pipeline.newPromise()

    @Volatile
    private var registered: Boolean = false
    // We start with the writability of the channel when creating the StreamChannel.
    @Volatile
    private var writable: Boolean = false

    private var outboundClosed: Boolean = false

    /**
     * This variable represents if a read is in progress for the current channel or was requested.
     * Note that depending upon the [RecvByteBufAllocator] behavior a read may extend beyond the
     * [ChannelUnsafe.beginRead] method scope. The [ChannelUnsafe.beginRead] loop may
     * drain all pending data, and then if the parent channel is reading this channel may still accept frames.
     */
    private var readStatus = ReadStatus.IDLE

    private var inboundBuffer: Queue<Any>? = null

    /** `true` after the first HEADERS frame has been written  */
    private var firstFrameWritten: Boolean = false

    // Currently the child channel and parent channel are always on the same EventLoop thread. This allows us to
    // extend the read loop of a child channel if the child channel drains its queued data during read, and the
    // parent channel is still in its read loop. The next/previous links build a doubly linked list that the parent
    // channel will iterate in its channelReadComplete to end the read cycle for each child channel in the list.
    internal var next: MultistreamChannel? = null
    internal var previous: MultistreamChannel? = null

//    internal fun DefaultHttp2StreamChannel: {
//        writable = initialWritability(stream)
//        (stream as Http2MultiplexCodecStream).channel = this
//        closePromise = pipeline.newPromise()
//        channelId = Http2StreamChannelId(parent().id(), ++idCount)
//    }

//    override fun stream(): Http2FrameStream {
//        return stream
//    }

    internal fun streamClosed() {
        unsafe.readEOS()
        // Attempt to drain any queued data from the queue and deliver it to the application before closing this
        // channel.
        unsafe.doBeginRead()
    }

    override fun metadata(): ChannelMetadata {
        return metadata
    }

    override fun config(): ChannelConfig {
        return config
    }

    override fun isOpen(): Boolean {
        return !closePromise.isDone
    }

    override fun isActive(): Boolean {
        return isOpen()
    }

    override fun isWritable(): Boolean {
        return writable
    }

    override fun id(): ChannelId {
        return channelId
    }

    override fun eventLoop(): EventLoop {
        return parent().eventLoop()
    }

    override fun parent(): Channel {
        return parentChannelContext.channel()
    }

    override fun isRegistered(): Boolean {
        return registered
    }

    override fun localAddress(): SocketAddress {
        return parent().localAddress()
    }

    override fun remoteAddress(): SocketAddress {
        return parent().remoteAddress()
    }

    override fun closeFuture(): ChannelFuture {
        return closePromise
    }

    override fun bytesBeforeUnwritable(): Long {
        // TODO: Do a proper impl
        return config().getWriteBufferHighWaterMark().toLong()
    }

    override fun bytesBeforeWritable(): Long {
        // TODO: Do a proper impl
        return 0
    }

    override fun unsafe(): Channel.Unsafe {
        return unsafe
    }

    override fun pipeline(): ChannelPipeline {
        return pipeline
    }

    override fun alloc(): ByteBufAllocator {
        return config().getAllocator()
    }

    override fun read(): Channel {
        pipeline().read()
        return this
    }

    override fun flush(): Channel {
        pipeline().flush()
        return this
    }

    override fun bind(localAddress: SocketAddress): ChannelFuture {
        return pipeline().bind(localAddress)
    }

    override fun connect(remoteAddress: SocketAddress): ChannelFuture {
        return pipeline().connect(remoteAddress)
    }

    override fun connect(remoteAddress: SocketAddress, localAddress: SocketAddress): ChannelFuture {
        return pipeline().connect(remoteAddress, localAddress)
    }

    override fun disconnect(): ChannelFuture {
        return pipeline().disconnect()
    }

    override fun close(): ChannelFuture {
        return pipeline().close()
    }

    override fun deregister(): ChannelFuture {
        return pipeline().deregister()
    }

    override fun bind(localAddress: SocketAddress, promise: ChannelPromise): ChannelFuture {
        return pipeline().bind(localAddress, promise)
    }

    override fun connect(remoteAddress: SocketAddress, promise: ChannelPromise): ChannelFuture {
        return pipeline().connect(remoteAddress, promise)
    }

    override fun connect(
        remoteAddress: SocketAddress,
        localAddress: SocketAddress,
        promise: ChannelPromise
    ): ChannelFuture {
        return pipeline().connect(remoteAddress, localAddress, promise)
    }

    override fun disconnect(promise: ChannelPromise): ChannelFuture {
        return pipeline().disconnect(promise)
    }

    override fun close(promise: ChannelPromise): ChannelFuture {
        return pipeline().close(promise)
    }

    override fun deregister(promise: ChannelPromise): ChannelFuture {
        return pipeline().deregister(promise)
    }

    override fun write(msg: Any): ChannelFuture {
        return pipeline().write(msg)
    }

    override fun write(msg: Any, promise: ChannelPromise): ChannelFuture {
        return pipeline().write(msg, promise)
    }

    override fun writeAndFlush(msg: Any, promise: ChannelPromise): ChannelFuture {
        return pipeline().writeAndFlush(msg, promise)
    }

    override fun writeAndFlush(msg: Any): ChannelFuture {
        return pipeline().writeAndFlush(msg)
    }

    override fun newPromise(): ChannelPromise {
        return pipeline().newPromise()
    }

    override fun newProgressivePromise(): ChannelProgressivePromise {
        return pipeline().newProgressivePromise()
    }

    override fun newSucceededFuture(): ChannelFuture {
        return pipeline().newSucceededFuture()
    }

    override fun newFailedFuture(cause: Throwable): ChannelFuture {
        return pipeline().newFailedFuture(cause)
    }

    override fun voidPromise(): ChannelPromise {
        return pipeline().voidPromise()
    }

    override fun hashCode(): Int {
        return id().hashCode()
    }

    override fun equals(o: Any?): Boolean {
        return this === o
    }

    override fun compareTo(o: Channel): Int {
        return if (this === o) {
            0
        } else id().compareTo(o.id())

    }

    override fun toString(): String {
        return parent().toString() + "(Multistream - " + channelId + ')'.toString()
    }

    internal fun writabilityChanged(writable: Boolean) {
        assert(eventLoop().inEventLoop())
        if (writable != this.writable && isActive()) {
            // Only notify if we received a state change.
            this.writable = writable
            pipeline().fireChannelWritabilityChanged()
        }
    }

    /**
     * Receive a read message. This does not notify handlers unless a read is in progress on the
     * channel.
     */
    internal fun fireChildRead(frame: Http2Frame) {
        assert(eventLoop().inEventLoop())
        if (!isActive) {
            ReferenceCountUtil.release(frame)
        } else if (readStatus != ReadStatus.IDLE) {
            // If a read is in progress or has been requested, there cannot be anything in the queue,
            // otherwise we would have drained it from the queue and processed it during the read cycle.
            assert(inboundBuffer == null || inboundBuffer!!.isEmpty())
            val allocHandle = unsafe.recvBufAllocHandle()
            unsafe.doRead0(frame, allocHandle)
            // We currently don't need to check for readEOS because the parent channel and child channel are limited
            // to the same EventLoop thread. There are a limited number of frame types that may come after EOS is
            // read (unknown, reset) and the trade off is less conditionals for the hot path (headers/data) at the
            // cost of additional readComplete notifications on the rare path.
            if (allocHandle.continueReading()) {
                tryAddChildChannelToReadPendingQueue(this)
            } else {
                tryRemoveChildChannelFromReadPendingQueue(this)
                unsafe.notifyReadComplete(allocHandle)
            }
        } else {
            if (inboundBuffer == null) {
                inboundBuffer = ArrayDeque(4)
            }
            inboundBuffer!!.add(frame)
        }
    }

    private fun tryRemoveChildChannelFromReadPendingQueue(multistreamChannel: MultistreamChannel): Nothing = TODO()
    private fun tryAddChildChannelToReadPendingQueue(multistreamChannel: MultistreamChannel) : Nothing = TODO()
    private fun isChildChannelInReadPendingQueue(multistreamChannel: MultistreamChannel): Boolean = TODO()
    private fun addChildChannelToReadPendingQueue(multistreamChannel: MultistreamChannel) : Nothing = TODO()

    internal fun fireChildReadComplete() {
        assert(eventLoop().inEventLoop())
        assert(readStatus != ReadStatus.IDLE)
        unsafe.notifyReadComplete(unsafe.recvBufAllocHandle())
    }

    private inner class ChannelUnsafe : Channel.Unsafe {
        private val unsafeVoidPromise = VoidChannelPromise(this@MultistreamChannel, false)
        private var recvHandle: RecvByteBufAllocator.Handle? = null
        private var writeDoneAndNoFlush: Boolean = false
        private var closeInitiated: Boolean = false
        private var readEOS: Boolean = false

        override fun connect(
            remoteAddress: SocketAddress,
            localAddress: SocketAddress, promise: ChannelPromise
        ) {
            if (!promise.setUncancellable()) {
                return
            }
            promise.setFailure(UnsupportedOperationException())
        }

        override fun recvBufAllocHandle(): RecvByteBufAllocator.Handle {
            if (recvHandle == null) {
                recvHandle = config().getRecvByteBufAllocator<RecvByteBufAllocator>().newHandle()
                recvHandle!!.reset(config())
            }
            return recvHandle!!
        }

        override fun localAddress(): SocketAddress {
            return parent().unsafe().localAddress()
        }

        override fun remoteAddress(): SocketAddress {
            return parent().unsafe().remoteAddress()
        }

        override fun register(eventLoop: EventLoop, promise: ChannelPromise) {
            if (!promise.setUncancellable()) {
                return
            }
            if (registered) {
                throw UnsupportedOperationException("Re-register is not supported")
            }

            registered = true

            if (!outbound) {
                // Add the handler to the pipeline now that we are registered.
                pipeline().addLast(inboundStreamHandler)
            }

            promise.setSuccess()

            pipeline().fireChannelRegistered()
            if (isActive()) {
                pipeline().fireChannelActive()
            }
        }

        override fun bind(localAddress: SocketAddress, promise: ChannelPromise) {
            if (!promise.setUncancellable()) {
                return
            }
            promise.setFailure(UnsupportedOperationException())
        }

        override fun disconnect(promise: ChannelPromise) {
            close(promise)
        }

        override fun close(promise: ChannelPromise) {
            if (!promise.setUncancellable()) {
                return
            }
            if (closeInitiated) {
                if (closePromise.isDone) {
                    // Closed already.
                    promise.setSuccess()
                } else if (promise !is VoidChannelPromise) { // Only needed if no VoidChannelPromise.
                    // This means close() was called before so we just register a listener and return
                    closePromise.addListener { promise.setSuccess() }
                }
                return
            }
            closeInitiated = true

            tryRemoveChildChannelFromReadPendingQueue(this@MultistreamChannel)

            val wasActive = isActive()

            if (inboundBuffer != null) {
                while (true) {
                    val msg = inboundBuffer!!.poll() ?: break
                    ReferenceCountUtil.release(msg)
                }
            }

            // The promise should be notified before we call fireChannelInactive().
            outboundClosed = true
            closePromise.setSuccess()
            promise.setSuccess()

            fireChannelInactiveAndDeregister(voidPromise(), wasActive)
        }

        override fun closeForcibly() {
            close(unsafe().voidPromise())
        }

        override fun deregister(promise: ChannelPromise) {
            fireChannelInactiveAndDeregister(promise, false)
        }

        private fun fireChannelInactiveAndDeregister(
            promise: ChannelPromise,
            fireChannelInactive: Boolean
        ) {
            if (!promise.setUncancellable()) {
                return
            }

            if (!registered) {
                promise.setSuccess()
                return
            }

            // As a user may call deregister() from within any method while doing processing in the ChannelPipeline,
            // we need to ensure we do the actual deregister operation later. This is necessary to preserve the
            // behavior of the AbstractChannel, which always invokes channelUnregistered and channelInactive
            // events 'later' to ensure the current events in the handler are completed before these events.
            //
            // See:
            // https://github.com/netty/netty/issues/4435
            invokeLater(Runnable {
                if (fireChannelInactive) {
                    pipeline.fireChannelInactive()
                }
                // The user can fire `deregister` events multiple times but we only want to fire the pipeline
                // event if the channel was actually registered.
                if (registered) {
                    registered = false
                    pipeline.fireChannelUnregistered()
                }
                safeSetSuccess(promise)
            })
        }

        private fun safeSetSuccess(promise: ChannelPromise) {
            if (promise !is VoidChannelPromise && !promise.trySuccess()) {
                println("Failed to mark a promise as success because it is done already: $promise")
            }
        }

        private fun invokeLater(task: Runnable) {
            try {
                // This method is used by outbound operation implementations to trigger an inbound event later.
                // They do not trigger an inbound event immediately because an outbound operation might have been
                // triggered by another inbound event handler method.  If fired immediately, the call stack
                // will look like this for example:
                //
                //   handlerA.inboundBufferUpdated() - (1) an inbound handler method closes a connection.
                //   -> handlerA.ctx.close()
                //     -> channel.unsafe.close()
                //       -> handlerA.channelInactive() - (2) another inbound handler method called while in (1) yet
                //
                // which means the execution of two inbound handler methods of the same handler overlap undesirably.
                eventLoop().execute(task)
            } catch (e: RejectedExecutionException) {
//                logger.warn("Can't invoke task later as EventLoop rejected it", e)
                e.printStackTrace()
            }

        }

        override fun beginRead() {
            if (!isActive()) {
                return
            }
            when (readStatus) {
                ReadStatus.IDLE -> {
                    readStatus = ReadStatus.IN_PROGRESS
                    doBeginRead()
                }
                ReadStatus.IN_PROGRESS -> readStatus = ReadStatus.REQUESTED
                else -> {
                }
            }
        }

        internal fun doBeginRead() {
            var message = inboundBuffer?.poll()
            if (message == null) {
                if (readEOS) {
                    unsafe.closeForcibly()
                }
            } else {
                val allocHandle = recvBufAllocHandle()
                allocHandle.reset(config())
                var continueReading = false
                do {
                    doRead0(message as Http2Frame, allocHandle)
                    continueReading = allocHandle.continueReading()
                    message = inboundBuffer!!.poll()
                } while ((readEOS || continueReading) && message != null)

                if (continueReading /*&& parentReadInProgress */&& !readEOS) {
                    // Currently the parent and child channel are on the same EventLoop thread. If the parent is
                    // currently reading it is possile that more frames will be delivered to this child channel. In
                    // the case that this child channel still wants to read we delay the channelReadComplete on this
                    // child channel until the parent is done reading.
                    assert(isChildChannelInReadPendingQueue(this@MultistreamChannel))
                    addChildChannelToReadPendingQueue(this@MultistreamChannel)
                } else {
                    notifyReadComplete(allocHandle)
                }
            }
        }

        internal fun readEOS() {
            readEOS = true
        }

        internal fun notifyReadComplete(allocHandle: RecvByteBufAllocator.Handle) {
            assert(next == null && previous == null)
            if (readStatus == ReadStatus.REQUESTED) {
                readStatus = ReadStatus.IN_PROGRESS
            } else {
                readStatus = ReadStatus.IDLE
            }
            allocHandle.readComplete()
            pipeline().fireChannelReadComplete()
            // Reading data may result in frames being written (e.g. WINDOW_UPDATE, RST, etc..). If the parent
            // channel is not currently reading we need to force a flush at the child channel, because we cannot
            // rely upon flush occurring in channelReadComplete on the parent channel.
            flush()
            if (readEOS) {
                unsafe.closeForcibly()
            }
        }

        internal fun doRead0(frame: Http2Frame, allocHandle: RecvByteBufAllocator.Handle) {
            pipeline().fireChannelRead(frame)
            allocHandle.incMessagesRead(1)

//            if (frame is Http2DataFrame) {
//                val numBytesToBeConsumed = frame.initialFlowControlledBytes()
//                allocHandle.attemptedBytesRead(numBytesToBeConsumed)
//                allocHandle.lastBytesRead(numBytesToBeConsumed)
//                if (numBytesToBeConsumed != 0) {
//                    try {
//                        writeDoneAndNoFlush = writeDoneAndNoFlush or consumeBytes(stream.id(), numBytesToBeConsumed)
//                    } catch (e: Http2Exception) {
//                        pipeline().fireExceptionCaught(e)
//                    }
//
//                }
//            } else {
//                allocHandle.attemptedBytesRead(MIN_HTTP2_FRAME_SIZE)
//                allocHandle.lastBytesRead(MIN_HTTP2_FRAME_SIZE)
//            }
        }

        override fun write(msg: Any, promise: ChannelPromise) {
            // After this point its not possible to cancel a write anymore.
            if (!promise.setUncancellable()) {
                ReferenceCountUtil.release(msg)
                return
            }

            if (!isActive() ||
                // Once the outbound side was closed we should not allow header / data frames
                outboundClosed && (msg is Http2HeadersFrame || msg is Http2DataFrame)
            ) {
                ReferenceCountUtil.release(msg)
                promise.setFailure(Libp2pException("Closed channel"))
                return
            }

            try {
                if (msg is Http2StreamFrame) {
                    val frame = validateStreamFrame(msg)/*.stream(stream())*/
                    if (!firstFrameWritten/* && !isStreamIdValid(stream().id())*/) {
                        if (frame !is Http2HeadersFrame) {
                            ReferenceCountUtil.release(frame)
                            promise.setFailure(
                                IllegalArgumentException("The first frame must be a headers frame. Was: " + frame.name())
                            )
                            return
                        }
                        firstFrameWritten = true
                        val future = write0(frame)
                        if (future.isDone) {
                            firstWriteComplete(future, promise)
                        } else {
                            future.addListener { f-> firstWriteComplete(future, promise) }
                        }
                        return
                    }
                } else {
                    val msgStr = msg.toString()
                    ReferenceCountUtil.release(msg)
                    promise.setFailure(
                        IllegalArgumentException(
                            "Message must be an " + StringUtil.simpleClassName(Http2StreamFrame::class.java) +
                                    ": " + msgStr
                        )
                    )
                    return
                }

                val future = write0(msg)
                if (future.isDone) {
                    writeComplete(future, promise)
                } else {
                    future.addListener { f -> writeComplete(future, promise) }
                }
            } catch (t: Throwable) {
                promise.tryFailure(t)
            } finally {
                writeDoneAndNoFlush = true
            }
        }

        private fun firstWriteComplete(future: ChannelFuture, promise: ChannelPromise) {
            val cause = future.cause()
            if (cause == null) {
                // As we just finished our first write which made the stream-id valid we need to re-evaluate
                // the writability of the channel.
//                writabilityChanged(this@MultistreamChannel.isWritable(stream))
                promise.setSuccess()
            } else {
                // If the first write fails there is not much we can do, just close
                closeForcibly()
                promise.setFailure(wrapStreamClosedError(cause))
            }
        }

        private fun writeComplete(future: ChannelFuture, promise: ChannelPromise) {
            val cause = future.cause()
            if (cause == null) {
                promise.setSuccess()
            } else {
                val error = wrapStreamClosedError(cause)
                // To make it more consistent with AbstractChannel we handle all IOExceptions here.
                if (error is IOException) {
                    if (config.isAutoClose) {
                        // Close channel if needed.
                        closeForcibly()
                    } else {
                        // TODO: Once Http2StreamChannel extends DuplexChannel we should call shutdownOutput(...)
                        outboundClosed = true
                    }
                }
                promise.setFailure(error)
            }
        }

        private fun wrapStreamClosedError(cause: Throwable): Throwable {
            // If the error was caused by STREAM_CLOSED we should use a ClosedChannelException to better
            // mimic other transports and make it easier to reason about what exceptions to expect.
            return if (cause is Http2Exception && cause.error() == Http2Error.STREAM_CLOSED) {
                ClosedChannelException().initCause(cause)
            } else cause
        }

        private fun validateStreamFrame(frame: Http2StreamFrame): Http2StreamFrame {
//            if (frame.stream() != null && frame.stream() !== stream) {
//                val msgString = frame.toString()
//                ReferenceCountUtil.release(frame)
//                throw IllegalArgumentException(
//                    "Stream " + frame.stream() + " must not be set on the frame: " + msgString
//                )
//            }
            return frame
        }

        private fun write0(msg: Any): ChannelFuture {
            val promise = parentChannelContext.newPromise()
            this@MultistreamChannel.write(msg, promise)
            return promise
        }

        override fun flush() {
            // If we are currently in the parent channel's read loop we should just ignore the flush.
            // We will ensure we trigger ctx.flush() after we processed all Channels later on and
            // so aggregate the flushes. This is done as ctx.flush() is expensive when as it may trigger an
            // write(...) or writev(...) operation on the socket.
//            if (!writeDoneAndNoFlush || parentReadInProgress) {
//                // There is nothing to flush so this is a NOOP.
//                return
//            }
            try {
//                flush0(parentChannelContext)
                parentChannelContext.flush()
            } finally {
                writeDoneAndNoFlush = false
            }
        }

        override fun voidPromise(): ChannelPromise {
            return unsafeVoidPromise
        }

        override fun outboundBuffer(): ChannelOutboundBuffer? {
            // Always return null as we not use the ChannelOutboundBuffer and not even support it.
            return null
        }
    }

    /**
     * [ChannelConfig] so that the high and low writebuffer watermarks can reflect the outbound flow control
     * window, without having to create a new [WriteBufferWaterMark] object whenever the flow control window
     * changes.
     */
    private inner class MultistreamChannelConfig internal constructor(channel: Channel) :
        DefaultChannelConfig(channel) {

//        override fun getWriteBufferHighWaterMark(): Int {
//            return min(parent().config().writeBufferHighWaterMark, initialOutboundStreamWindow)
//        }
//
//        override fun getWriteBufferLowWaterMark(): Int {
//            return min(parent().config().writeBufferLowWaterMark, initialOutboundStreamWindow)
//        }
//
//        override fun getMessageSizeEstimator(): MessageSizeEstimator {
//            return FlowControlledFrameSizeEstimator.INSTANCE
//        }

        override fun getWriteBufferWaterMark(): WriteBufferWaterMark {
            val mark = writeBufferHighWaterMark
            return WriteBufferWaterMark(mark, mark)
        }

        override fun setMessageSizeEstimator(estimator: MessageSizeEstimator): ChannelConfig {
            throw UnsupportedOperationException()
        }

        @Deprecated("")
        override fun setWriteBufferHighWaterMark(writeBufferHighWaterMark: Int): ChannelConfig {
            throw UnsupportedOperationException()
        }

        @Deprecated("")
        override fun setWriteBufferLowWaterMark(writeBufferLowWaterMark: Int): ChannelConfig {
            throw UnsupportedOperationException()
        }

        override fun setWriteBufferWaterMark(writeBufferWaterMark: WriteBufferWaterMark): ChannelConfig {
            throw UnsupportedOperationException()
        }

        override fun setRecvByteBufAllocator(allocator: RecvByteBufAllocator): ChannelConfig {
            if (allocator.newHandle() !is RecvByteBufAllocator.ExtendedHandle) {
                throw IllegalArgumentException("allocator.newHandle() must return an object of type: " + RecvByteBufAllocator.ExtendedHandle::class.java)
            }
            super.setRecvByteBufAllocator(allocator)
            return this
        }
    }
}

class MultistreamChannelPipeline(val channel: Channel): DefaultChannelPipeline(channel) {
    override fun incrementPendingOutboundBytes(size: Long) {
        // Do thing for now
    }

    override fun decrementPendingOutboundBytes(size: Long) {
        // Do thing for now
    }
}

private enum class ReadStatus {
    IDLE,
    IN_PROGRESS,
    REQUESTED
}
