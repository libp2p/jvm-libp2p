package io.libp2p.mux.yamux

import java.util.concurrent.atomic.AtomicLong

class YamuxStreamIdGenerator(connectionInitiator: Boolean) {

    private val idCounter = AtomicLong(if (connectionInitiator) 1L else 2L) // 0 is reserved

    fun next() = idCounter.getAndAdd(2)

    companion object {
        fun isRemoteSynStreamIdValid(isRemoteConnectionInitiator: Boolean, id: Long) =
            id > 0 && (if (isRemoteConnectionInitiator) id % 2 == 1L else id % 2 == 0L)
    }
}
