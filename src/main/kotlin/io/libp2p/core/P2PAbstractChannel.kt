package io.libp2p.core

import io.netty.channel.Channel

abstract class P2PAbstractChannel(val ch: Channel) {
    val isInitiator by lazy {
        ch.attr(IS_INITIATOR)?.get() ?: throw Libp2pException("Internal error: missing channel attribute IS_INITIATOR")
    }
}
