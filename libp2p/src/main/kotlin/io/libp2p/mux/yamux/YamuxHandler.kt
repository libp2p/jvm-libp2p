package io.libp2p.mux.yamux

import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.StreamPromise
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.CONNECTION
import io.libp2p.etc.STREAM
import io.libp2p.etc.types.forward
import io.libp2p.etc.types.sliceMaxSize
import io.libp2p.etc.util.netty.mux.AbstractMuxHandler
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.etc.util.netty.mux.MuxChannelInitializer
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

const val INITIAL_WINDOW_SIZE = 256 * 1024

open class YamuxHandler(
    protected val multistreamProtocol: MultistreamProtocol,
    protected val maxFrameDataLength: Int,
    private val ready: CompletableFuture<StreamMuxer.Session>?,
    inboundStreamHandler: StreamHandler<*>,
    initiator: Boolean
) : AbstractMuxHandler<ByteBuf>(), StreamMuxer.Session {
    private val idGenerator = AtomicInteger(if (initiator) 1 else 2) // 0 is reserved
    private val receiveWindow = AtomicInteger(INITIAL_WINDOW_SIZE)
    private val sendWindow = AtomicInteger(INITIAL_WINDOW_SIZE)
    private val lock = Semaphore(1)

    override val inboundInitializer: MuxChannelInitializer<ByteBuf> = {
        inboundStreamHandler.handleStream(createStream(it))
    }

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        super.handlerAdded(ctx)
        ready?.complete(this)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as YamuxFrame
        when (msg.type) {
            YamuxType.DATA -> handleDataRead(msg)
            YamuxType.WINDOW_UPDATE -> handleWindowUpdate(msg)
            YamuxType.PING -> handlePing(msg)
            YamuxType.GO_AWAY -> onRemoteClose(msg.id)
        }
    }

    fun handlePing(msg: YamuxFrame) {
        val ctx = getChannelHandlerContext()
        when (msg.flags) {
            YamuxFlags.SYN -> ctx.write(YamuxFrame(MuxId(msg.id.parentId, 0, msg.id.initiator), YamuxType.PING, YamuxFlags.ACK, msg.lenData))
            YamuxFlags.ACK -> {}
        }
    }

    fun handleFlags(msg: YamuxFrame) {
        val ctx = getChannelHandlerContext()
        if (msg.flags == YamuxFlags.SYN) {
            // ACK the new stream
            onRemoteOpen(msg.id)
            ctx.write(YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, YamuxFlags.ACK, 0))
        }
        if (msg.flags == YamuxFlags.FIN)
            onRemoteDisconnect(msg.id)
    }

    fun handleDataRead(msg: YamuxFrame) {
        val ctx = getChannelHandlerContext()
        val size = msg.lenData
        handleFlags(msg)
        if (size == 0)
            return
        val newWindow = receiveWindow.addAndGet(-size)
        if (newWindow < INITIAL_WINDOW_SIZE / 2) {
            val delta = INITIAL_WINDOW_SIZE / 2
            receiveWindow.addAndGet(delta)
            ctx.write(YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, 0, delta))
            ctx.flush()
        }
        childRead(msg.id, msg.data!!)
    }

    fun handleWindowUpdate(msg: YamuxFrame) {
        handleFlags(msg)
        val size = msg.lenData
        sendWindow.addAndGet(size)
        lock.release()
    }

    override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf) {
        val ctx = getChannelHandlerContext()
        while (sendWindow.get() <= 0) {
            // wait until the window is increased
            lock.acquire()
        }
        data.sliceMaxSize(minOf(maxFrameDataLength, sendWindow.get()))
            .map { frameSliceBuf ->
                sendWindow.addAndGet(-frameSliceBuf.readableBytes())
                YamuxFrame(child.id, YamuxType.DATA, 0, frameSliceBuf.readableBytes(), frameSliceBuf)
            }.forEach { muxFrame ->
                ctx.write(muxFrame)
            }
        ctx.flush()
    }

    override fun onLocalOpen(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.SYN, 0))
    }

    override fun onLocalDisconnect(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.FIN, 0))
    }

    override fun onLocalClose(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.RST, 0))
    }

    override fun onRemoteCreated(child: MuxChannel<ByteBuf>) {
    }

    override fun generateNextId() =
        MuxId(getChannelHandlerContext().channel().id(), idGenerator.addAndGet(2).toLong(), true)

    private fun createStream(channel: MuxChannel<ByteBuf>): Stream {
        val connection = ctx!!.channel().attr(CONNECTION).get()
        val stream = StreamOverNetty(channel, connection, channel.initiator)
        channel.attr(STREAM).set(stream)
        return stream
    }

    override fun <T> createStream(protocols: List<ProtocolBinding<T>>): StreamPromise<T> {
        return createStream(multistreamProtocol.createMultistream(protocols).toStreamHandler())
    }

    fun <T> createStream(streamHandler: StreamHandler<T>): StreamPromise<T> {
        val controller = CompletableFuture<T>()
        val stream = newStream {
            streamHandler.handleStream(createStream(it)).forward(controller)
        }.thenApply { it.attr(STREAM).get() }
        return StreamPromise(stream, controller)
    }
}
