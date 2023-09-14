package io.libp2p.mux.yamux

import java.util.concurrent.atomic.AtomicLong

class YamuxStreamIdGenerator(connectionInitiator: Boolean) {

    private val idCounter = AtomicLong(if (connectionInitiator) 1L else 2L)  // 0 is reserved

    fun next() = idCounter.getAndAdd(2)
}