package io.libp2p.pubsub.partial

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PartialMessageTest {

    @Test
    fun `BitwiseOrMerger should merge null with value`() {
        val result = BitwiseOrMerger.merge(null, byteArrayOf(0b10101010.toByte()))
        assertThat(result).isEqualTo(byteArrayOf(0b10101010.toByte()))
    }

    @Test
    fun `BitwiseOrMerger should OR two values`() {
        val a = byteArrayOf(0b11110000.toByte())
        val b = byteArrayOf(0b00001111)
        val result = BitwiseOrMerger.merge(a, b)
        assertThat(result).isEqualTo(byteArrayOf(0b11111111.toByte()))
    }

    @Test
    fun `BitwiseOrMerger should handle different lengths`() {
        val a = byteArrayOf(0b11110000.toByte())
        val b = byteArrayOf(0b00001111, 0b11111111.toByte())
        val result = BitwiseOrMerger.merge(a, b)
        assertThat(result).isEqualTo(byteArrayOf(0b11111111.toByte(), 0b11111111.toByte()))
    }

    @Test
    fun `PartialPublishAction equality`() {
        val a = PartialPublishAction(true, byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val b = PartialPublishAction(true, byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `PartialPublishAction inequality`() {
        val a = PartialPublishAction(true, byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val b = PartialPublishAction(false, byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        assertThat(a).isNotEqualTo(b)
    }
}
