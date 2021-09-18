package io.libp2p.etc.types

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ByteBufExtTest {

    companion object {
        @JvmStatic
        fun byteBufCases(): List<ByteBuf> {
            val sampleBytes = ByteArray(100) { it.toByte() }.toByteBuf()
            return listOf(
                Unpooled.buffer(),
                sampleBytes.slice(0, 1),
                sampleBytes.slice(0, 2),
                sampleBytes.slice(0, 3),
                sampleBytes.slice(0, 4),
                sampleBytes.slice(0, 5),
                sampleBytes.slice(0, 6),
                sampleBytes.slice(0, 7),
                sampleBytes.slice(0, 100),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("byteBufCases")
    fun testSliceMaxSize(bufCase: ByteBuf) {
        val buf = bufCase.copy()
        val slices = buf.sliceMaxSize(3)

        assertThat(slices.map { it.readableBytes() })
            .allMatch { it <= 3 }
            .allMatch { it > 0 }
        val slicesConcat = Unpooled.wrappedBuffer(*slices.toTypedArray()).toByteArray()
        assertThat(slicesConcat.contentEquals(bufCase.toByteArray())).isTrue()

        slices.forEach { it.release() }

        assertThat(slices).allMatch { it.refCnt() == 0 }
        assertThat(buf.refCnt()).isZero()
    }
}
