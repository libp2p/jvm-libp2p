package io.libp2p.mux.yamux

import io.libp2p.core.ConnectionClosedException
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
    private val connectionInitiator: Boolean,
    private val maxBufferedConnectionWrites: Int
) : MuxHandler(ready, inboundStreamHandler) {
    private val idGenerator = YamuxStreamIdGenerator(connectionInitiator)
    private val sendWindowSizes = ConcurrentHashMap<MuxId, AtomicInteger>()
    private val sendBuffers = ConcurrentHashMap<MuxId, SendBuffer>()
    private val receiveWindowSizes = ConcurrentHashMap<MuxId, AtomicInteger>()

    /**
     * Would contain GoAway error code when received, or would be completed with [ConnectionClosedException]
     * when the connection closed without GoAway message
     */
    val goAwayPromise = CompletableFuture<Long>()

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
            bufferedData.forEach { releaseMessage(it) }
            bufferedData.clear()
        }
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        sendWindowSizes.clear()
        sendBuffers.values.forEach { it.close() }
        sendBuffers.clear()
        receiveWindowSizes.clear()
        if (!goAwayPromise.isDone) {
            goAwayPromise.completeExceptionally(ConnectionClosedException("Connection was closed without Go Away message"))
        }
        super.channelUnregistered(ctx)
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
                    YamuxId.sessionId(msg.id.parentId),
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
        val windowSize = receiveWindowSizes[msg.id] ?: run {
            releaseMessage(msg.data!!)
            throw Libp2pException("Unable to retrieve receive window size for ${msg.id}")
        }
        val newWindow = windowSize.addAndGet(-size)
        // send a window update frame once half of the window is depleted
        if (newWindow < INITIAL_WINDOW_SIZE / 2) {
            val delta = INITIAL_WINDOW_SIZE - newWindow
            windowSize.addAndGet(delta)
            val frame = YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, 0, delta.toLong())
            getChannelHandlerContext().writeAndFlush(frame)
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

    private fun validateSynRemoteMuxId(id: MuxId) {
        val isRemoteConnectionInitiator = !connectionInitiator
        if (!YamuxStreamIdGenerator.isRemoteSynStreamIdValid(isRemoteConnectionInitiator, id.id)) {
            getChannelHandlerContext().close()
            throw Libp2pException("Invalid remote SYN StreamID: $id, isRemoteInitiator: $isRemoteConnectionInitiator")
        }
    }

    private fun handleFlags(msg: YamuxFrame) {
        val ctx = getChannelHandlerContext()
        when (msg.flags) {
            YamuxFlags.SYN -> {
                validateSynRemoteMuxId(msg.id)
                onRemoteYamuxOpen(msg.id)
                // ACK the new stream
                ctx.writeAndFlush(YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, YamuxFlags.ACK, 0))
            }

            YamuxFlags.FIN -> onRemoteDisconnect(msg.id)
            YamuxFlags.RST -> onRemoteClose(msg.id)
        }
    }

    private fun handleGoAway(msg: YamuxFrame) {
        goAwayPromise.complete(msg.length)
    }

    override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf) {
        val windowSize = sendWindowSizes[child.id] ?: run {
            releaseMessage(data)
            throw Libp2pException("Unable to retrieve send window size for ${child.id}")
        }

        sendData(child, windowSize, data)
    }

    private fun calculateTotalBufferedWrites(): Int {
        return sendBuffers.values.sumOf { it.bufferedBytes() }
    }

    private fun sendData(
        child: MuxChannel<ByteBuf>,
        windowSize: AtomicInteger,
        data: ByteBuf
    ) {
        data.sliceMaxSize(maxFrameDataLength)
            .forEach { slicedData ->
                if (windowSize.get() > 0) {
                    val length = slicedData.readableBytes()
                    windowSize.addAndGet(-length)
                    val frame = YamuxFrame(child.id, YamuxType.DATA, 0, length.toLong(), slicedData)
                    getChannelHandlerContext().writeAndFlush(frame)
                } else {
                    // wait until the window is increased to send
                    addToSendBuffer(child, data)
                }
            }
    }

    private fun addToSendBuffer(child: MuxChannel<ByteBuf>, data: ByteBuf) {
        val buffer = sendBuffers.getOrPut(child.id) { SendBuffer(child.id) }
        buffer.add(data)
        val totalBufferedWrites = calculateTotalBufferedWrites()
        if (totalBufferedWrites > maxBufferedConnectionWrites) {
            onLocalClose(child)
            throw Libp2pException(
                "Overflowed send buffer ($totalBufferedWrites/$maxBufferedConnectionWrites) for connection ${
                    getChannelHandlerContext().channel().id().asLongText()
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
        val windowSize = sendWindowSizes.remove(child.id)
        val sendBuffer = sendBuffers.remove(child.id)
        if (windowSize != null && sendBuffer != null) {
            sendBuffer.flush(windowSize)
            sendBuffer.close()
        }
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.FIN, 0))
    }

    override fun onLocalClose(child: MuxChannel<ByteBuf>) {
        // close stream immediately so not transferring buffered data
        sendWindowSizes.remove(child.id)
        sendBuffers.remove(child.id)?.close()
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.RST, 0))
    }

    override fun onChildClosed(child: MuxChannel<ByteBuf>) {
        sendWindowSizes.remove(child.id)
        sendBuffers.remove(child.id)?.close()
        receiveWindowSizes.remove(child.id)
    }

    override fun generateNextId() =
        YamuxId(getChannelHandlerContext().channel().id(), idGenerator.next())
}
