package io.libp2p.mux.yamux

import io.libp2p.core.Libp2pException
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.types.sliceMaxSize
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.MuxHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

const val INITIAL_WINDOW_SIZE = 256 * 1024
const val MAX_BUFFERED_CONNECTION_WRITES = 1024 * 1024

open class YamuxHandler(
    override val multistreamProtocol: MultistreamProtocol,
    override val maxFrameDataLength: Int,
    ready: CompletableFuture<StreamMuxer.Session>?,
    inboundStreamHandler: StreamHandler<*>,
    initiator: Boolean
) : MuxHandler(ready, inboundStreamHandler) {
    private val idGenerator = AtomicInteger(if (initiator) 1 else 2) // 0 is reserved
    private val windowSizes = ConcurrentHashMap<MuxId, AtomicInteger>()
    private val sendBuffers = ConcurrentHashMap<MuxId, SendBuffer>()

    inner class SendBuffer(val id: MuxId, val ctx: ChannelHandlerContext) {
        private val bufferedData = ArrayDeque<ByteBuf>()

        fun add(data: ByteBuf) {
            bufferedData.add(data.retain())
        }

        fun bufferedBytes(): Int {
            return bufferedData.sumOf { it.readableBytes() }
        }

        fun flush(windowSize: AtomicInteger) {
            while (!bufferedData.isEmpty()) {
                val data = bufferedData.first()
                val length = data.readableBytes()
                if (length <= windowSize.get()) {
                    sendBlocks(ctx, data, windowSize, id)
                    data.release()
                    bufferedData.removeFirst()
                } else {
                    // partial write to fit within window
                    val toRead = windowSize.get()
                    if (toRead > 0) {
                        val partialData = data.readSlice(toRead)
                        sendBlocks(ctx, partialData, windowSize, id)
                    }
                    break
                }
            }
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as YamuxFrame
        when (msg.type) {
            YamuxType.DATA -> handleDataRead(msg)
            YamuxType.WINDOW_UPDATE -> handleWindowUpdate(msg)
            YamuxType.PING -> handlePing(msg)
            YamuxType.GO_AWAY -> handleGoAway(msg)
        }
    }

    private fun handlePing(msg: YamuxFrame) {
        val ctx = getChannelHandlerContext()
        when (msg.flags) {
            YamuxFlags.SYN -> ctx.writeAndFlush(
                YamuxFrame(
                    MuxId(msg.id.parentId, 0, msg.id.initiator),
                    YamuxType.PING,
                    YamuxFlags.ACK,
                    msg.length
                )
            )

            YamuxFlags.ACK -> {}
        }
    }

    private fun handleDataRead(msg: YamuxFrame) {
        val ctx = getChannelHandlerContext()
        handleFlags(msg)
        val size = msg.length.toInt()
        if (size == 0) {
            return
        }
        val windowSize = windowSizes[msg.id]
        if (windowSize == null) {
            releaseMessage(msg.data!!)
            throw Libp2pException("No window size initialised for ${msg.id}")
        }
        val newWindow = windowSize.addAndGet(-size)
        // send a window update frame once half of the window is depleted
        if (newWindow < INITIAL_WINDOW_SIZE / 2) {
            val delta = INITIAL_WINDOW_SIZE - newWindow
            windowSize.addAndGet(delta)
            ctx.write(YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, 0, delta.toLong()))
            ctx.flush()
        }
        childRead(msg.id, msg.data!!)
    }

    private fun handleWindowUpdate(msg: YamuxFrame) {
        handleFlags(msg)
        val delta = msg.length.toInt()
        if (delta == 0) {
            return
        }
        val windowSize = windowSizes[msg.id] ?: return
        windowSize.addAndGet(delta)
        val buffer = sendBuffers[msg.id] ?: return
        // try to send any buffered messages after the window update
        buffer.flush(windowSize)
    }

    private fun handleFlags(msg: YamuxFrame) {
        val ctx = getChannelHandlerContext()
        when (msg.flags) {
            YamuxFlags.SYN -> {
                onRemoteYamuxOpen(msg.id)
                // ACK the new stream
                ctx.writeAndFlush(YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, YamuxFlags.ACK, 0))
            }

            YamuxFlags.FIN -> onRemoteDisconnect(msg.id)
            YamuxFlags.RST -> onRemoteClose(msg.id)
        }
    }

    private fun handleGoAway(msg: YamuxFrame) {
        onRemoteClose(msg.id)
    }

    override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf) {
        val ctx = getChannelHandlerContext()

        val windowSize = windowSizes[child.id] ?: throw Libp2pException("No window size initialised for " + child.id)

        if (windowSize.get() <= 0) {
            // wait until the window is increased to send more data
            val buffer = sendBuffers.getOrPut(child.id) { SendBuffer(child.id, ctx) }
            buffer.add(data)
            if (calculateTotalBufferedWrites() > MAX_BUFFERED_CONNECTION_WRITES) {
                throw Libp2pException("Overflowed send buffer for connection")
            }
            return
        }
        sendBlocks(ctx, data, windowSize, child.id)
    }

    private fun calculateTotalBufferedWrites(): Int {
        return sendBuffers.values.sumOf { it.bufferedBytes() }
    }

    fun sendBlocks(ctx: ChannelHandlerContext, data: ByteBuf, windowSize: AtomicInteger, id: MuxId) {
        data.sliceMaxSize(minOf(windowSize.get(), maxFrameDataLength))
            .map { slicedData ->
                val length = slicedData.readableBytes()
                windowSize.addAndGet(-length)
                YamuxFrame(id, YamuxType.DATA, 0, length.toLong(), slicedData)
            }.forEach { muxFrame ->
                ctx.write(muxFrame)
            }
        ctx.flush()
    }

    override fun onLocalOpen(child: MuxChannel<ByteBuf>) {
        onStreamCreate(child.id)
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.SYN, 0))
    }

    private fun onRemoteYamuxOpen(id: MuxId) {
        onStreamCreate(id)
        onRemoteOpen(id)
    }

    private fun onStreamCreate(id: MuxId) {
        windowSizes.putIfAbsent(id, AtomicInteger(INITIAL_WINDOW_SIZE))
    }

    override fun onLocalDisconnect(child: MuxChannel<ByteBuf>) {
        // transfer buffered data before sending FIN
        val windowSize = windowSizes[child.id]
        val sendBuffer = sendBuffers.remove(child.id)
        if (windowSize != null && sendBuffer != null) {
            sendBuffer.flush(windowSize)
        }
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.FIN, 0))
    }

    override fun onLocalClose(child: MuxChannel<ByteBuf>) {
        // close stream immediately so not transferring buffered data
        sendBuffers.remove(child.id)
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.RST, 0))
    }

    override fun onChildClosed(child: MuxChannel<ByteBuf>) {
        windowSizes.remove(child.id)
        sendBuffers.remove(child.id)
    }

    override fun generateNextId() =
        MuxId(getChannelHandlerContext().channel().id(), idGenerator.addAndGet(2).toLong(), true)
}
