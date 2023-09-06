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
    private val receiveWindows = ConcurrentHashMap<MuxId, AtomicInteger>()
    private val sendWindows = ConcurrentHashMap<MuxId, AtomicInteger>()
    private val sendBuffers = ConcurrentHashMap<MuxId, SendBuffer>()
    private val totalBufferedWrites = AtomicInteger()

    inner class SendBuffer(val ctx: ChannelHandlerContext) {
        private val buffered = ArrayDeque<ByteBuf>()

        fun add(data: ByteBuf) {
            buffered.add(data.retain())
        }

        fun flush(sendWindow: AtomicInteger, id: MuxId): Int {
            var written = 0
            while (!buffered.isEmpty()) {
                val buf = buffered.first()
                val bufLength = buf.readableBytes()
                if (bufLength <= sendWindow.get()) {
                    sendBlocks(ctx, buf, sendWindow, id)
                    written += bufLength
                    buf.release()
                    buffered.removeFirst()
                } else {
                    // partial write to fit within window
                    val toRead = sendWindow.get()
                    if (toRead > 0) {
                        val partialBuf = buf.readSlice(toRead)
                        sendBlocks(ctx, partialBuf, sendWindow, id)
                        written += toRead
                    }
                    break
                }
            }
            return written
        }

        fun totalBytes(): Int {
            return buffered.sumOf { it.readableBytes() }
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
        val recWindow = receiveWindows[msg.id]
        if (recWindow == null) {
            releaseMessage(msg.data!!)
            throw Libp2pException("No receive window for ${msg.id}")
        }
        val newWindow = recWindow.addAndGet(-size)
        // send a window update frame once half of the window is depleted
        if (newWindow < INITIAL_WINDOW_SIZE / 2) {
            val delta = INITIAL_WINDOW_SIZE - newWindow
            recWindow.addAndGet(delta)
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
        val sendWindow = sendWindows[msg.id] ?: return
        sendWindow.addAndGet(delta)
        val buffer = sendBuffers[msg.id] ?: return
        // try to send any buffered messages after the window update
        val writtenBytes = buffer.flush(sendWindow, msg.id)
        totalBufferedWrites.addAndGet(-writtenBytes)
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

        val sendWindow = sendWindows[child.id] ?: throw Libp2pException("No send window for " + child.id)

        if (sendWindow.get() <= 0) {
            // wait until the window is increased to send more data
            val buffer = sendBuffers.getOrPut(child.id) { SendBuffer(ctx) }
            buffer.add(data)
            if (totalBufferedWrites.addAndGet(data.readableBytes()) > MAX_BUFFERED_CONNECTION_WRITES) {
                throw Libp2pException("Overflowed send buffer for connection")
            }
            return
        }
        sendBlocks(ctx, data, sendWindow, child.id)
    }

    fun sendBlocks(ctx: ChannelHandlerContext, data: ByteBuf, sendWindow: AtomicInteger, id: MuxId) {
        data.sliceMaxSize(minOf(sendWindow.get(), maxFrameDataLength))
            .map { slicedData ->
                val length = slicedData.readableBytes()
                sendWindow.addAndGet(-length)
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

    private fun onStreamCreate(childId: MuxId) {
        receiveWindows.putIfAbsent(childId, AtomicInteger(INITIAL_WINDOW_SIZE))
        sendWindows.putIfAbsent(childId, AtomicInteger(INITIAL_WINDOW_SIZE))
    }

    override fun onLocalDisconnect(child: MuxChannel<ByteBuf>) {
        flushAndClearSendBuffers(child.id)
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.FIN, 0))
    }

    private fun flushAndClearSendBuffers(id: MuxId) {
        val sendWindow = sendWindows.remove(id)
        val buffer = sendBuffers.remove(id)
        if (buffer != null && sendWindow != null) {
            val writtenBytes = buffer.flush(sendWindow, id)
            totalBufferedWrites.addAndGet(-writtenBytes)
        }
    }

    override fun onLocalClose(child: MuxChannel<ByteBuf>) {
        clearSendBuffers(child.id)
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.RST, 0))
    }

    override fun onChildClosed(child: MuxChannel<ByteBuf>) {
        sendWindows.remove(child.id)
        receiveWindows.remove(child.id)
        clearSendBuffers(child.id)
    }

    private fun clearSendBuffers(id: MuxId) {
        sendBuffers.remove(id)?.let {
            totalBufferedWrites.addAndGet(-it.totalBytes())
        }
    }

    override fun generateNextId() =
        MuxId(getChannelHandlerContext().channel().id(), idGenerator.addAndGet(2).toLong(), true)
}
