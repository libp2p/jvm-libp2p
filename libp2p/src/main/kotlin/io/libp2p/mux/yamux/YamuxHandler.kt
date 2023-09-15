package io.libp2p.mux.yamux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.types.sliceMaxSize
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.InvalidFrameMuxerException
import io.libp2p.mux.MuxHandler
import io.libp2p.mux.UnknownStreamIdMuxerException
import io.libp2p.mux.WriteBufferOverflowMuxerException
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
    private val maxBufferedConnectionWrites: Int,
    private val initialWindowSize: Int = INITIAL_WINDOW_SIZE
) : MuxHandler(ready, inboundStreamHandler) {
    private val idGenerator = YamuxStreamIdGenerator(connectionInitiator)

    private val streamHandlers = ConcurrentHashMap<MuxId, YamuxStreamHandler>()

    private inner class YamuxStreamHandler(
        val id: MuxId
    ) {
        val sendWindowSize = AtomicInteger(initialWindowSize)
        val receiveWindowSize = AtomicInteger(initialWindowSize)
        val sendBuffer = SendBuffer(id)

        fun dispose() {
            sendBuffer.dispose()
        }

        fun handleDataRead(msg: YamuxFrame) {
            handleFlags(msg)

            val size = msg.length.toInt()
            if (size == 0) {
                return
            }

            val newWindow = receiveWindowSize.addAndGet(-size)
            // send a window update frame once half of the window is depleted
            if (newWindow < initialWindowSize / 2) {
                val delta = initialWindowSize - newWindow
                receiveWindowSize.addAndGet(delta)
                writeAndFlushFrame(YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, 0, delta.toLong()))
            }
            childRead(msg.id, msg.data!!)
        }

        fun handleWindowUpdate(msg: YamuxFrame) {
            handleFlags(msg)

            val delta = msg.length.toInt()
            if (delta == 0) {
                return
            }

            sendWindowSize.addAndGet(delta)
            // try to send any buffered messages after the window update
            sendBuffer.flush(sendWindowSize)
        }

        private fun handleFlags(msg: YamuxFrame) {
            when (msg.flags) {
                YamuxFlags.SYN -> {
                    // ACK the new stream
                    writeAndFlushFrame(YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, YamuxFlags.ACK, 0))
                }

                YamuxFlags.FIN -> onRemoteDisconnect(msg.id)
                YamuxFlags.RST -> onRemoteClose(msg.id)
            }
        }

        fun sendData(
            data: ByteBuf
        ) {
            data.sliceMaxSize(maxFrameDataLength)
                .forEach { slicedData ->
                    if (sendWindowSize.get() > 0) {
                        val length = slicedData.readableBytes()
                        sendWindowSize.addAndGet(-length)
                        writeAndFlushFrame(YamuxFrame(id, YamuxType.DATA, 0, length.toLong(), slicedData))
                    } else {
                        // wait until the window is increased to send
                        addToSendBuffer(data)
                    }
                }
        }

        private fun addToSendBuffer(data: ByteBuf) {
            sendBuffer.add(data)
            val totalBufferedWrites = calculateTotalBufferedWrites()
            if (totalBufferedWrites > maxBufferedConnectionWrites) {
                onLocalClose()
                throw WriteBufferOverflowMuxerException(
                    "Overflowed send buffer ($totalBufferedWrites/$maxBufferedConnectionWrites) for connection ${id.asLongText()}"
                )
            }
        }

        fun onLocalOpen() {
            writeAndFlushFrame(YamuxFrame(id, YamuxType.DATA, YamuxFlags.SYN, 0))
        }

        fun onRemoteOpen() {
            // nothing
        }

        fun onLocalDisconnect() {
            // TODO: this implementation drops remaining data
            sendBuffer.flush(sendWindowSize)
            sendBuffer.dispose()
            writeAndFlushFrame(YamuxFrame(id, YamuxType.DATA, YamuxFlags.FIN, 0))
        }

        fun onLocalClose() {
            // close stream immediately so not transferring buffered data
            sendBuffer.dispose()
            writeAndFlushFrame(YamuxFrame(id, YamuxType.DATA, YamuxFlags.RST, 0))
        }
    }

    private inner class SendBuffer(val id: MuxId) {
        private val bufferedData = ArrayDeque<ByteBuf>()

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
                writeAndFlushFrame(YamuxFrame(id, YamuxType.DATA, 0, length.toLong(), data))
            }
        }

        fun dispose() {
            bufferedData.forEach { releaseMessage(it) }
            bufferedData.clear()
        }
    }

    /**
     * Would contain GoAway error code when received, or would be completed with [ConnectionClosedException]
     * when the connection closed without GoAway message
     */
    val goAwayPromise = CompletableFuture<Long>()

    private fun getStreamHandlerOrThrow(id: MuxId): YamuxStreamHandler =
        streamHandlers[id] ?: throw UnknownStreamIdMuxerException(id)

    private fun getStreamHandlerOrReleaseAndThrow(id: MuxId, msgToRelease: ByteBuf?): YamuxStreamHandler =
        streamHandlers[id] ?: run {
            if (msgToRelease != null) {
                releaseMessage(msgToRelease)
            }
            throw UnknownStreamIdMuxerException(id)
        }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        streamHandlers.values.forEach { it.dispose() }

        if (!goAwayPromise.isDone) {
            goAwayPromise.completeExceptionally(ConnectionClosedException("Connection was closed without Go Away message"))
        }
        super.channelUnregistered(ctx)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as YamuxFrame

        when (msg.type) {
            YamuxType.PING -> handlePing(msg)
            YamuxType.GO_AWAY -> handleGoAway(msg)
            else -> {
                if (msg.flags == YamuxFlags.SYN) {
                    // remote opens a new stream
                    validateSynRemoteMuxId(msg.id)
                    onRemoteYamuxOpen(msg.id)
                }

                val streamHandler = getStreamHandlerOrReleaseAndThrow(msg.id, msg.data)
                when (msg.type) {
                    YamuxType.DATA -> streamHandler.handleDataRead(msg)
                    YamuxType.WINDOW_UPDATE -> streamHandler.handleWindowUpdate(msg)
                }
            }
        }
    }

    private fun writeAndFlushFrame(yamuxFrame: YamuxFrame) {
        getChannelHandlerContext().writeAndFlush(yamuxFrame)
    }

    private fun abruptlyCloseConnection() {
        getChannelHandlerContext().close()
    }

    private fun validateSynRemoteMuxId(id: MuxId) {
        val isRemoteConnectionInitiator = !connectionInitiator
        if (!YamuxStreamIdGenerator.isRemoteSynStreamIdValid(isRemoteConnectionInitiator, id.id)) {
            abruptlyCloseConnection()
            throw Libp2pException("Invalid remote SYN StreamID: $id, isRemoteInitiator: $isRemoteConnectionInitiator")
        }
    }

    override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf) {
        getStreamHandlerOrReleaseAndThrow(child.id, data).sendData(data)
    }

    override fun onLocalOpen(child: MuxChannel<ByteBuf>) {
        createYamuxStreamHandler(child.id).onLocalOpen()
    }

    private fun onRemoteYamuxOpen(id: MuxId) {
        createYamuxStreamHandler(id).onRemoteOpen()
        onRemoteOpen(id)
    }

    private fun createYamuxStreamHandler(id: MuxId): YamuxStreamHandler {
        val streamHandler = YamuxStreamHandler(id)
        streamHandlers[id] = streamHandler
        return streamHandler
    }

    override fun onLocalDisconnect(child: MuxChannel<ByteBuf>) {
        getStreamHandlerOrThrow(child.id).onLocalDisconnect()
    }

    override fun onLocalClose(child: MuxChannel<ByteBuf>) {
        streamHandlers.remove(child.id)?.onLocalClose()
    }

    override fun onChildClosed(child: MuxChannel<ByteBuf>) {
        streamHandlers.remove(child.id)?.dispose()
    }

    private fun calculateTotalBufferedWrites(): Int {
        return streamHandlers.values.sumOf { it.sendBuffer.bufferedBytes() }
    }

    private fun handlePing(msg: YamuxFrame) {
        if (msg.id.id != YamuxId.SESSION_STREAM_ID) {
            throw InvalidFrameMuxerException("Invalid StreamId for Ping frame type: ${msg.id}")
        }
        when (msg.flags) {
            YamuxFlags.SYN -> writeAndFlushFrame(
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

    private fun handleGoAway(msg: YamuxFrame) {
        if (msg.id.id != YamuxId.SESSION_STREAM_ID) {
            throw InvalidFrameMuxerException("Invalid StreamId for Ping frame type: ${msg.id}")
        }
        goAwayPromise.complete(msg.length)
    }

    override fun generateNextId() =
        YamuxId(getChannelHandlerContext().channel().id(), idGenerator.next())
}
