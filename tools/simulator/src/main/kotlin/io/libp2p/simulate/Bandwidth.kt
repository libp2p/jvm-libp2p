package io.libp2p.simulate

import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class Bandwidth(val bytesPerSecond: Long) {
    fun getTransmitTimeMillis(size: Long): Long = (size * 1000 / bytesPerSecond)
    fun getTransmitTime(size: Long): Duration = getTransmitTimeMillis(size).milliseconds

    fun getTransmitSize(timeMillis: Long): Long =
        bytesPerSecond * timeMillis / 1000

    operator fun div(d: Int) = Bandwidth(bytesPerSecond / d)

    companion object {
        fun mbitsPerSec(mbsec: Int) = Bandwidth(mbsec.toLong() * (1 shl 20) / 10)
    }
}

interface BandwidthDelayer : MessageDelayer {

    val totalBandwidth: Bandwidth

    companion object {
        val UNLIM_BANDWIDTH = object : BandwidthDelayer {
            override val totalBandwidth = Bandwidth(Long.MAX_VALUE)
            override fun delay(size: Long) = CompletableFuture.completedFuture(Unit)
        }
    }
}
