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
const val DEFAULT_MAX_BUFFERED_CONNECTION_WRITES = 10 * 1024 * 1024 // 10 MiB

open class YamuxHandler(
    override val multistreamProtocol: MultistreamProtocol,
    override val maxFrameDataLength: Int,
    ready: CompletableFuture<StreamMuxer.Session>?,
    inboundStreamHandler: StreamHandler<*>,
    initiator: Boolean,
    private val maxBufferedConnectionWrites: Int
) : MuxHandler(ready, inboundStreamHandler) {
    private val idGenerator = AtomicInteger(if (initiator) 1 else 2) // 0 is reserved
    private val sendWindowSizes = ConcurrentHashMap<MuxId, AtomicInteger>()
    private val sendBuffers = ConcurrentHashMap<MuxId, SendBuffer>()
    private val receiveWindowSizes = ConcurrentHashMap<MuxId, AtomicInteger>()

    private inner class SendBuffer(val id: MuxId) {
        private val bufferedData = ArrayDeque<ByteBuf>()
        private val ctx = getChannelHandlerContext()

        fun add(data: ByteBuf) {
            bufferedData.add(data)
        }

        fun bufferedBytes(): Int {
            return bufferedData.sumOf { it.readableBytes() }
        }

        fun flush(windowSize: AtomicInteger) {
            while (!bufferedData.isEmpty() && windowSize.get() > 0) {
                val data = bufferedData.removeFirst()
                val length = data.readableBytes()
                windowSize.addAndGet(-length)
                val frame = YamuxFrame(id, YamuxType.DATA, 0, length.toLong(), data)
                ctx.writeAndFlush(frame)
            }
        }

        fun close() {
            bufferedData.forEach { it.release() }
            bufferedData.clear()
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
        handleFlags(msg)
        val size = msg.length.toInt()
        if (size == 0) {
            return
        }
        val windowSize = receiveWindowSizes[msg.id]
        if (windowSize == null) {
            releaseMessage(msg.data!!)
            throw Libp2pException("Unable to retrieve receive window size for ${msg.id}")
        }
        val ctx = getChannelHandlerContext()
        val newWindow = windowSize.addAndGet(-size)
        // send a window update frame once half of the window is depleted
        if (newWindow < INITIAL_WINDOW_SIZE / 2) {
            val delta = INITIAL_WINDOW_SIZE - newWindow
            windowSize.addAndGet(delta)
            val frame = YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, 0, delta.toLong())
            ctx.writeAndFlush(frame)
        }
        childRead(msg.id, msg.data!!)
    }

    private fun handleWindowUpdate(msg: YamuxFrame) {
        handleFlags(msg)
        val delta = msg.length.toInt()
        if (delta == 0) {
            return
        }
        val windowSize =
            sendWindowSizes[msg.id] ?: throw Libp2pException("Unable to retrieve send window size for ${msg.id}")
        windowSize.addAndGet(delta)
        // try to send any buffered messages after the window update
        sendBuffers[msg.id]?.flush(windowSize)
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

        val windowSize =
            sendWindowSizes[child.id] ?: throw Libp2pException("Unable to retrieve send window size for ${child.id}")

        sendData(ctx, data, windowSize, child.id)
    }

    private fun calculateTotalBufferedWrites(): Int {
        return sendBuffers.values.sumOf { it.bufferedBytes() }
    }

    private fun sendData(ctx: ChannelHandlerContext, data: ByteBuf, windowSize: AtomicInteger, id: MuxId) {
        data.sliceMaxSize(maxFrameDataLength)
            .forEach { slicedData ->
                if (windowSize.get() > 0) {
                    val length = slicedData.readableBytes()
                    windowSize.addAndGet(-length)
                    val frame = YamuxFrame(id, YamuxType.DATA, 0, length.toLong(), slicedData)
                    ctx.writeAndFlush(frame)
                } else {
                    // wait until the window is increased to send
                    addToSendBuffer(id, data, ctx)
                }
            }
    }

    private fun addToSendBuffer(id: MuxId, data: ByteBuf, ctx: ChannelHandlerContext) {
        val buffer = sendBuffers.getOrPut(id) { SendBuffer(id) }
        buffer.add(data)
        val totalBufferedWrites = calculateTotalBufferedWrites()
        if (totalBufferedWrites > maxBufferedConnectionWrites) {
            buffer.close()
            throw Libp2pException(
                "Overflowed send buffer ($totalBufferedWrites/$maxBufferedConnectionWrites) for connection ${
                    ctx.channel().id().asLongText()
                }"
            )
        }
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
        sendWindowSizes.putIfAbsent(id, AtomicInteger(INITIAL_WINDOW_SIZE))
        receiveWindowSizes.putIfAbsent(id, AtomicInteger(INITIAL_WINDOW_SIZE))
    }

    override fun onLocalDisconnect(child: MuxChannel<ByteBuf>) {
        // transfer buffered data before sending FIN
        val windowSize = sendWindowSizes[child.id]
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
        sendWindowSizes.remove(child.id)
        sendBuffers.remove(child.id)
        receiveWindowSizes.remove(child.id)
    }

    override fun generateNextId() =
        MuxId(getChannelHandlerContext().channel().id(), idGenerator.addAndGet(2).toLong(), true)
}
