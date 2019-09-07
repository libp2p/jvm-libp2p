package io.libp2p.core.dsl

import io.libp2p.host.HostImpl
import java.util.function.Consumer

/**
 * Creates Java friendly [io.libp2p.core.Host] builder
 */
fun hostJ(fn: Consumer<BuilderJ>): HostImpl {
    val builder = BuilderJ()
    fn.accept(builder)
    return builder.build()
}

class BuilderJ : Builder() {
    public override val identity = super.identity
    public override val secureChannels = super.secureChannels
    public override val muxers = super.muxers
    public override val transports = super.transports
    public override val addressBook = super.addressBook
    public override val protocols = super.protocols
    public override val connectionHandlers = super.connectionHandlers
    public override val network = super.network
    public override val debug = super.debug
}