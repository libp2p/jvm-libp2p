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
package io.libp2p.core.mplex

import io.libp2p.core.protocol.Protocols
import io.libp2p.core.wip.MplexFrame
import io.netty.channel.ChannelHandlerContext

/**
 * @param streamId the ID of the stream.
 * @param initiator whether this peer is the initiator of this stream.
 * @param name the name of this stream.
 */
class MultiplexStream(val streamId: Long, val initiator: Boolean, val name: String = "") {

    /**
     * The state if this stream.
     */
    var state = MultiplexStreamState.READY

    var chatStreamId = 100L // temp

    // TODO: implement a protocol handler
    fun handle(
        ctx: ChannelHandlerContext,
        protocols: List<String>,
        payload: ByteArray?
    ) {

        if (protocols.contains(Protocols.IPFS_ID_1_0_0)) {
            var frame = MplexFrame.createMessage(
                initiator,
                streamId,
                Protocols.MULTISTREAM_1_0_0,
                Protocols.IPFS_ID_1_0_0,
                "na"
            )
            ctx!!.writeAndFlush(frame)
        } else if (protocols.contains(Protocols.CHAT_1_0_0)) {
            ctx!!.writeAndFlush(
                MplexFrame.createMessage(
                    initiator,
                    chatStreamId,
                    "Hello there!"
                )
            )
        } else if (protocols.contains(Protocols.MULTISTREAM_1_0_0)) {
            ctx!!.writeAndFlush(
                MplexFrame.createMessage(
                    initiator,
                    chatStreamId,
                    Protocols.MULTISTREAM_1_0_0,
                    Protocols.CHAT_1_0_0
                )
            )
        } else {
            println("DATA EVENT: handle it $payload")
        }
    }

    fun closeRemote() {
        TODO("not implemented")
    }

    fun resetRemote() {
        // TODO: need the channel! How to obtain it? Should we set it in the constructor?
        TODO("not implemented")
    }

    fun updateState(state: MultiplexStreamState) {
        this.state = state
    }
}