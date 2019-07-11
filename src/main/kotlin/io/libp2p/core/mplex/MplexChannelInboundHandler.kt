/*
 * Copyright 2019 BLK Technologies Limited (web3labs.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.libp2p.core.wip

import io.libp2p.core.Libp2pException
import io.libp2p.core.events.ProtocolNegotiationFailed
import io.libp2p.core.events.ProtocolNegotiationSucceeded
import io.libp2p.core.mplex.MplexChannelInitializer
import io.libp2p.core.protocol.Protocols
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter

/**
 * A [ChannelInboundHandler] implementation that observes events relating the mplex protocol negotiation.
 */
class MplexChannelInboundHandler : ChannelInboundHandlerAdapter() {

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        when (evt) {
            is ProtocolNegotiationSucceeded -> {
                if (evt.proto.trim() == Protocols.MPLEX_6_7_0) {
                    ctx.pipeline().replace(
                        this, "MplexChannelInitializer",
                        MplexChannelInitializer()
                    )
                }
            }

            is ProtocolNegotiationFailed -> throw Libp2pException("ProtocolNegotiationFailed: $evt")
        }
        super.userEventTriggered(ctx, evt)
    }

}