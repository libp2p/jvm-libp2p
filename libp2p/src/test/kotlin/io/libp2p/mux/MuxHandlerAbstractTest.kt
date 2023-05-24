package io.libp2p.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.StreamHandler
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.getX
import io.libp2p.etc.types.toHex
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.mux.MuxHandlerAbstractTest.AbstractTestMuxFrame.Flag.*
import io.libp2p.tools.TestChannel
import io.libp2p.tools.readAllBytesAndRelease
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Index
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * Created by Anton Nashatyrev on 09.07.2019.
 */
abstract class MuxHandlerAbstractTest {
    val childHandlers = mutableListOf<TestHandler>()
    lateinit var multistreamHandler: MuxHandler
    lateinit var ech: TestChannel
    val parentChannelId get() = ech.id()

    val allocatedBufs = mutableListOf<ByteBuf>()

    abstract val maxFrameDataLength: Int
    abstract fun createMuxHandler(streamHandler: StreamHandler<*>): MuxHandler

    fun createTestStreamHandler(): StreamHandler<TestHandler>  =
        StreamHandler { stream ->
            val handler = TestHandler()
            stream.pushHandler(nettyInitializer {
                it.addLastLocal(handler)
            })
            CompletableFuture.completedFuture(handler)
        }

    fun <T> StreamHandler<T>.onNewStream(block: (T) -> Unit): StreamHandler<T> =
        StreamHandler { stream ->
            this.handleStream(stream)
                .thenApply {
                    block(it)
                    it
                }
        }

    @BeforeEach
    fun startMultiplexor() {
        val streamHandler = createTestStreamHandler()
            .onNewStream {
                childHandlers += it
            }
        multistreamHandler = createMuxHandler(streamHandler)

        ech = TestChannel("test", true, LoggingHandler(LogLevel.ERROR), multistreamHandler)
    }

    @AfterEach
    open fun cleanUpAndCheck() {
        childHandlers.clear()

        allocatedBufs.forEach {
            assertThat(it.refCnt()).isEqualTo(1)
        }
        allocatedBufs.clear()
    }

    data class AbstractTestMuxFrame(
        val streamId: Long,
        val flag: Flag,
        val data: String = ""
    ) {
        enum class Flag { Open, Data, Close, Reset}
    }

    abstract fun writeFrame(frame: AbstractTestMuxFrame)
    abstract fun readFrame(): AbstractTestMuxFrame?
    fun readFrameOrThrow() = readFrame() ?: throw AssertionError("No outbound frames")

    fun openStream(id: Long) = writeFrame(AbstractTestMuxFrame(id, Open))
    fun writeStream(id: Long, msg: String) = writeFrame(AbstractTestMuxFrame(id, Data, msg))
    fun closeStream(id: Long) = writeFrame(AbstractTestMuxFrame(id, Close))
    fun resetStream(id: Long) = writeFrame(AbstractTestMuxFrame(id, Reset))

    fun openStreamByLocal(): TestHandler {
        val handlerFut = multistreamHandler.createStream(createTestStreamHandler()).controller
        ech.runPendingTasks()
        return handlerFut.get()
    }

    protected fun allocateBuf(): ByteBuf {
        val buf = Unpooled.buffer()
        buf.retain() // ref counter to 2 to check that exactly 1 ref remains at the end
        allocatedBufs += buf
        return buf
    }

    fun assertHandlerCount(count: Int) = assertEquals(count, childHandlers.size)
    fun assertLastMessage(handler: Int, msgCount: Int, msg: String) {
        val messages = childHandlers[handler].inboundMessages
        assertEquals(msgCount, messages.size)
        assertEquals(msg, messages.last())
    }

    @Test
    fun singleStream() {
        openStream(12)
        assertHandlerCount(1)
        assertTrue(childHandlers[0].isActivated)

        writeStream(12, "22")
        assertHandlerCount(1)
        assertEquals(1, childHandlers[0].inboundMessages.size)
        assertEquals("22", childHandlers[0].inboundMessages.last())

        writeStream(12, "44")
        assertHandlerCount(1)
        assertEquals(2, childHandlers[0].inboundMessages.size)
        assertEquals("44", childHandlers[0].inboundMessages.last())

        writeStream(12, "66")
        assertHandlerCount(1)
        assertEquals(3, childHandlers[0].inboundMessages.size)
        assertEquals("66", childHandlers[0].inboundMessages.last())

        assertFalse(childHandlers[0].isInactivated)
        assertTrue(childHandlers[0].exceptions.isEmpty())
    }

    @Test
    fun `test that readComplete event is fired to child channel`() {
        openStream(12)

        assertThat(childHandlers[0].readCompleteEventCount).isZero()

        writeStream(12, "22")

        assertThat(childHandlers[0].readCompleteEventCount).isEqualTo(1)

        writeStream(12, "23")

        assertThat(childHandlers[0].readCompleteEventCount).isEqualTo(2)
    }

    @Test
    fun `test that readComplete event is fired to reading channels only`() {
        openStream(12)
        openStream(13)

        assertThat(childHandlers[0].readCompleteEventCount).isZero()
        assertThat(childHandlers[1].readCompleteEventCount).isZero()

        writeStream(12, "22")

        assertThat(childHandlers[0].readCompleteEventCount).isEqualTo(1)
        assertThat(childHandlers[1].readCompleteEventCount).isEqualTo(0)

        writeStream(13, "23")

        assertThat(childHandlers[0].readCompleteEventCount).isEqualTo(1)
        assertThat(childHandlers[1].readCompleteEventCount).isEqualTo(1)
    }

    @Test
    fun twoStreamsInterleaved() {
        openStream(12)
        writeStream(12, "22")

        assertHandlerCount(1)
        assertLastMessage(0, 1, "22")

        writeStream(12, "23")
        assertHandlerCount(1)
        assertLastMessage(0, 2, "23")

        openStream(22)
        writeStream(22, "33")
        assertHandlerCount(2)
        assertLastMessage(1, 1, "33")

        writeStream(12, "24")
        assertHandlerCount(2)
        assertLastMessage(0, 3, "24")

        writeStream(22, "34")
        assertHandlerCount(2)
        assertLastMessage(1, 2, "34")

        assertFalse(childHandlers[0].isInactivated)
        assertTrue(childHandlers[0].exceptions.isEmpty())
        assertFalse(childHandlers[1].isInactivated)
        assertTrue(childHandlers[1].exceptions.isEmpty())
    }

    @Test
    fun twoStreamsSequential() {
        openStream(12)
        writeStream(12, "22")

        assertHandlerCount(1)
        assertLastMessage(0, 1, "22")

        writeStream(12, "23")
        assertHandlerCount(1)
        assertLastMessage(0, 2, "23")

        writeStream(12, "24")
        assertHandlerCount(1)
        assertLastMessage(0, 3, "24")

        writeStream(12, "25")
        assertHandlerCount(1)
        assertLastMessage(0, 4, "25")

        assertFalse(childHandlers[0].isInactivated)
        resetStream(12)
        assertTrue(childHandlers[0].isHandlerRemoved)
        assertTrue(childHandlers[0].exceptions.isEmpty())

        openStream(22)
        writeStream(22, "33")
        assertHandlerCount(2)
        assertLastMessage(1, 1, "33")

        writeStream(22, "34")
        assertHandlerCount(2)
        assertLastMessage(1, 2, "34")

        assertFalse(childHandlers[1].isInactivated)
        resetStream(22)
        assertTrue(childHandlers[1].isHandlerRemoved)
        assertTrue(childHandlers[1].exceptions.isEmpty())
    }

    @Test
    fun streamIsReset() {
        openStream(22)
        assertFalse(childHandlers[0].ctx.channel().closeFuture().isDone)
        assertFalse(childHandlers[0].isInactivated)

        resetStream(22)
        assertTrue(childHandlers[0].ctx.channel().closeFuture().isDone)
        assertTrue(childHandlers[0].isHandlerRemoved)
    }

    @Test
    fun streamIsResetWhenChannelIsClosed() {
        openStream(22)
        assertFalse(childHandlers[0].ctx.channel().closeFuture().isDone)

        ech.close().await()

        assertTrue(childHandlers[0].ctx.channel().closeFuture().isDone)
        assertTrue(childHandlers[0].isHandlerRemoved)
        assertTrue(childHandlers[0].exceptions.isEmpty())
    }

    @Test
    fun cantReceiveOnResetStream() {
        openStream(18)
        resetStream(18)

        assertThrows(Libp2pException::class.java) {
            writeStream(18, "35")
        }
        assertTrue(childHandlers[0].isHandlerRemoved)
    }

    @Test
    fun cantReceiveOnClosedStream() {
        openStream(18)
        closeStream(18)

        assertThrows(Libp2pException::class.java) {
            writeStream(18, "35")
        }
        assertFalse(childHandlers[0].isInactivated)
    }

    @Test
    fun cantReceiveOnNonExistentStream() {
        assertThrows(Libp2pException::class.java) {
            writeStream(92, "35")
        }
        assertHandlerCount(0)
    }

    @Test
    fun canResetNonExistentStream() {
        resetStream(99)
        assertHandlerCount(0)
    }

    @Test
    fun cantOpenStreamOnClosedChannel() {
        ech.close().await()

        val staleStream =
            multistreamHandler.createStream {
                println("This shouldn't be displayed: parent stream is closed")
                CompletableFuture.completedFuture(Unit)
            }

        assertThrows(ConnectionClosedException::class.java) { staleStream.stream.getX(3.0) }
        assertHandlerCount(0)
    }

    @Test
    fun `local create and after local disconnect should still read`() {
        val handler = openStreamByLocal()
        handler.ctx.writeAndFlush("1984".fromHex().toByteBuf(allocateBuf()))
        handler.ctx.disconnect().sync()

        val openFrame = readFrameOrThrow()
        assertThat(openFrame.flag).isEqualTo(Open)

        val dataFrame = readFrameOrThrow()
        assertThat(dataFrame.flag).isEqualTo(Data)
        assertThat(dataFrame.streamId).isEqualTo(openFrame.streamId)

        val closeFrame = readFrameOrThrow()
        assertThat(closeFrame.flag).isEqualTo(Close)

        assertThat(readFrame()).isNull()
        assertThat(handler.isInactivated).isTrue()
        assertThat(handler.isUnregistered).isFalse()
        assertThat(handler.inboundMessages).isEmpty()

        writeStream(dataFrame.streamId, "1122")
        assertThat(handler.inboundMessages).isNotEmpty
    }

    @Test
    fun `local create and after remote disconnect should still write`() {
        val handler = openStreamByLocal()

        val openFrame = readFrameOrThrow()
        assertThat(openFrame.flag).isEqualTo(Open)
        assertThat(readFrame()).isNull()

        closeStream(openFrame.streamId)

        assertThat(handler.isInactivated).isFalse()
        assertThat(handler.isUnregistered).isFalse()
        assertThat(handler.userEvents).containsExactly(RemoteWriteClosed)

        handler.ctx.writeAndFlush("1984".fromHex().toByteBuf(allocateBuf()))

        val readFrame = readFrameOrThrow()
        assertThat(readFrame.flag).isEqualTo(Data)
        assertThat(readFrame.data).isEqualTo("1984")
        assertThat(readFrame()).isNull()
    }

    @Test
    fun `test remote and local disconnect closes stream`() {
        val handler = openStreamByLocal()
        handler.ctx.disconnect().sync()

        readFrameOrThrow()
        val closeFrame = readFrameOrThrow()
        assertThat(closeFrame.flag).isEqualTo(Close)

        assertThat(handler.isInactivated).isTrue()
        assertThat(handler.isUnregistered).isFalse()

        closeStream(closeFrame.streamId)

        assertThat(handler.isHandlerRemoved).isTrue()
    }

    @Test
    fun `test large message is split onto slices`() {
        val handler = openStreamByLocal()
        readFrameOrThrow()

        val largeMessage = "42".repeat(maxFrameDataLength - 1) + "4344"
        handler.ctx.writeAndFlush(largeMessage.fromHex().toByteBuf(allocateBuf()))

        val dataFrame1 = readFrameOrThrow()
        assertThat(dataFrame1.data.fromHex())
            .hasSize(maxFrameDataLength)
            .contains(0x42, Index.atIndex(0))
            .contains(0x42, Index.atIndex(maxFrameDataLength - 2))
            .contains(0x43, Index.atIndex(maxFrameDataLength - 1))

        val dataFrame2 = readFrameOrThrow()
        assertThat(dataFrame2.data.fromHex())
            .hasSize(1)
            .contains(0x44, Index.atIndex(0))

        assertThat(readFrame()).isNull()
    }

    @Test
    fun `should throw when writing to locally closed stream`() {
        val handler = openStreamByLocal()
        handler.ctx.disconnect()

        assertThrows(Exception::class.java) {
            handler.ctx.writeAndFlush("42".fromHex().toByteBuf(allocateBuf())).sync()
        }
    }

    @Test
    fun `should throw when writing to reset stream`() {
        val handler = openStreamByLocal()
        handler.ctx.close()

        assertThrows(Exception::class.java) {
            handler.ctx.writeAndFlush("42".fromHex().toByteBuf(allocateBuf())).sync()
        }
    }

    @Test
    fun `should throw when writing to closed connection`() {
        val handler = openStreamByLocal()
        ech.close().sync()

        assertThrows(Exception::class.java) {
            handler.ctx.writeAndFlush("42".fromHex().toByteBuf(allocateBuf())).sync()
        }
    }

    class TestHandler : ChannelInboundHandlerAdapter() {
        val inboundMessages = mutableListOf<String>()
        lateinit var ctx: ChannelHandlerContext
        var readCompleteEventCount = 0

        val exceptions = mutableListOf<Throwable>()
        val userEvents = mutableListOf<Any>()
        var isHandlerAdded = false
        var isRegistered = false
        var isActivated = false
        var isInactivated = false
        var isUnregistered = false
        var isHandlerRemoved = false

        init {
            println("New child channel created")
        }

        override fun handlerAdded(ctx: ChannelHandlerContext) {
            assertFalse(isHandlerAdded)
            isHandlerAdded = true
            println("MultiplexHandlerTest.handlerAdded")
            this.ctx = ctx
        }

        override fun channelRegistered(ctx: ChannelHandlerContext?) {
            assertTrue(isHandlerAdded)
            assertFalse(isRegistered)
            isRegistered = true
            println("MultiplexHandlerTest.channelRegistered")
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            assertTrue(isRegistered)
            assertFalse(isActivated)
            isActivated = true
            println("MultiplexHandlerTest.channelActive")
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            assertTrue(isActivated)
            println("MultiplexHandlerTest.channelRead")
            msg as ByteBuf
            inboundMessages += msg.readAllBytesAndRelease().toHex()
        }

        override fun channelReadComplete(ctx: ChannelHandlerContext?) {
            readCompleteEventCount++
            println("MultiplexHandlerTest.channelReadComplete")
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            userEvents += evt
            println("MultiplexHandlerTest.userEventTriggered: $evt")
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            exceptions += cause
            println("MultiplexHandlerTest.exceptionCaught")
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            assertTrue(isActivated)
            assertFalse(isInactivated)
            isInactivated = true
            println("MultiplexHandlerTest.channelInactive")
        }

        override fun channelUnregistered(ctx: ChannelHandlerContext?) {
            assertTrue(isInactivated)
            assertFalse(isUnregistered)
            isUnregistered = true
            println("MultiplexHandlerTest.channelUnregistered")
        }

        override fun handlerRemoved(ctx: ChannelHandlerContext?) {
            assertTrue(isUnregistered)
            assertFalse(isHandlerRemoved)
            isHandlerRemoved = true
            println("MultiplexHandlerTest.handlerRemoved")
        }
    }

    companion object {
        fun ByteArray.toByteBuf(buf: ByteBuf): ByteBuf = buf.writeBytes(this)
    }
}
