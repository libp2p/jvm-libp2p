package io.libp2p.etc.util.netty

import io.libp2p.tools.readAllBytesAndRelease
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ByteBufQueueTest {

    val queue = ByteBufQueue()

    val allocatedBufs = mutableListOf<ByteBuf>()

    @AfterEach
    fun cleanUpAndCheck() {
        allocatedBufs.forEach {
            assertThat(it.refCnt()).isEqualTo(1)
        }
    }

    fun allocateBuf(): ByteBuf {
        val buf = Unpooled.buffer()
        buf.retain() // ref counter to 2 to check that exactly 1 ref remains at the end
        allocatedBufs += buf
        return buf
    }

    fun allocateData(data: String): ByteBuf =
        allocateBuf().writeBytes(data.toByteArray())

    fun ByteBuf.readString() = String(this.readAllBytesAndRelease())

    @Test
    fun emptyTest() {
        assertThat(queue.take(100).readString()).isEqualTo("")
    }

    @Test
    fun zeroTest() {
        queue.push(allocateData("abc"))
        assertThat(queue.take(0).readString()).isEqualTo("")
        assertThat(queue.take(100).readString()).isEqualTo("abc")
    }

    @Test
    fun emptyZeroTest() {
        assertThat(queue.take(0).readString()).isEqualTo("")
    }

    @Test
    fun emptyBuffersTest1() {
        queue.push(allocateData(""))
        assertThat(queue.take(10).readString()).isEqualTo("")
    }

    @Test
    fun emptyBuffersTest2() {
        queue.push(allocateData(""))
        assertThat(queue.take(0).readString()).isEqualTo("")
    }

    @Test
    fun emptyBuffersTest3() {
        queue.push(allocateData(""))
        queue.push(allocateData("a"))
        queue.push(allocateData(""))
        assertThat(queue.take(10).readString()).isEqualTo("a")
    }

    @Test
    fun emptyBuffersTest4() {
        queue.push(allocateData("a"))
        queue.push(allocateData(""))
        assertThat(queue.take(10).readString()).isEqualTo("a")
    }

    @Test
    fun emptyBuffersTest5() {
        queue.push(allocateData("a"))
        queue.push(allocateData(""))
        assertThat(queue.take(1).readString()).isEqualTo("a")
        assertThat(queue.take(1).readString()).isEqualTo("")
    }

    @Test
    fun emptyBuffersTest6() {
        queue.push(allocateData("a"))
        queue.push(allocateData(""))
        queue.push(allocateData(""))
        queue.push(allocateData("b"))
        assertThat(queue.take(10).readString()).isEqualTo("ab")
    }

    @Test
    fun pushTake1() {
        queue.push(allocateData("abc"))
        queue.push(allocateData("def"))

        assertThat(queue.take(4).readString()).isEqualTo("abcd")
        assertThat(queue.take(1).readString()).isEqualTo("e")
        assertThat(queue.take(100).readString()).isEqualTo("f")
        assertThat(queue.take(100).readString()).isEqualTo("")
    }

    @Test
    fun pushTake2() {
        queue.push(allocateData("abc"))
        queue.push(allocateData("def"))

        assertThat(queue.take(2).readString()).isEqualTo("ab")
        assertThat(queue.take(2).readString()).isEqualTo("cd")
        assertThat(queue.take(2).readString()).isEqualTo("ef")
        assertThat(queue.take(2).readString()).isEqualTo("")
    }

    @Test
    fun pushTake3() {
        queue.push(allocateData("abc"))
        queue.push(allocateData("def"))

        assertThat(queue.take(1).readString()).isEqualTo("a")
        assertThat(queue.take(1).readString()).isEqualTo("b")
        assertThat(queue.take(1).readString()).isEqualTo("c")
        assertThat(queue.take(1).readString()).isEqualTo("d")
        assertThat(queue.take(1).readString()).isEqualTo("e")
        assertThat(queue.take(1).readString()).isEqualTo("f")
        assertThat(queue.take(1).readString()).isEqualTo("")
    }

    @Test
    fun pushTakePush1() {
        queue.push(allocateData("abc"))
        assertThat(queue.take(2).readString()).isEqualTo("ab")
        queue.push(allocateData("def"))
        assertThat(queue.take(2).readString()).isEqualTo("cd")
        assertThat(queue.take(100).readString()).isEqualTo("ef")
    }

    @Test
    fun pushTakePush2() {
        queue.push(allocateData("abc"))
        assertThat(queue.take(3).readString()).isEqualTo("abc")
        queue.push(allocateData("def"))
        assertThat(queue.take(2).readString()).isEqualTo("de")
        assertThat(queue.take(100).readString()).isEqualTo("f")
    }

    @Test
    fun pushTakePush3() {
        queue.push(allocateData("abc"))
        queue.push(allocateData("def"))
        assertThat(queue.take(1).readString()).isEqualTo("a")
        queue.push(allocateData("ghi"))
        assertThat(queue.take(100).readString()).isEqualTo("bcdefghi")
    }

    @Test
    fun pushTakePush4() {
        queue.push(allocateData("abc"))
        assertThat(queue.take(1).readString()).isEqualTo("a")
        queue.push(allocateData("def"))
        queue.push(allocateData("ghi"))
        assertThat(queue.take(100).readString()).isEqualTo("bcdefghi")
    }
}
