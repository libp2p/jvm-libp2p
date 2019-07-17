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

import io.libp2p.core.Libp2pException
import io.libp2p.core.types.readUvarint
import io.libp2p.core.wip.MplexFrame
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter

/**
 * TODO: open questions/items:
 * <ol>
 *      <li>perhaps this should be the Multiplexer?</li>
 *      <li>we need to add in exception handling so that states can be reverted</li>
 *      <li>Do we want an instance of this handler/multiplexor to be for the whole app, or one instance per channel?</li>
 *      <li>Don't accept any data once channelActive() is called unless it is over a stream</li>
 *      <li>add better logging!</li>
 *      <li>add a timer to remove closed/reset streams after 30 seconds perhaps</li>
 * </ol>
 */
class MplexFrameHandler : ChannelInboundHandlerAdapter() {

    /**
     * A map from stream ID to the stream instance.
     */
    private val mapOfStreams = mutableMapOf<Long, MultiplexStream>()

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
        msg as MplexFrame
        val currentStream = mapOfStreams[msg.streamId]

        if (msg.flag == MplexFlags.NewStream) {
            if (currentStream != null) {
                // We should reject this new clashing stream id.
                resetStream(ctx, currentStream)
            } else {
                acceptStream(msg.streamId, msg.dataString)
            }
        } else {
            if (currentStream == null) {
                throw Libp2pException("No stream found for id=${msg.streamId}")
            }

            // Being lazy - subtracting 1 makes our comparison simpler.
            when (if (currentStream.initiator) msg.flag else msg.flag - 1) {
                MplexFlags.MessageReceiver -> {
                    val protocolsAndPayload = parseFrame(msg.data)
                    val protocols = protocolsAndPayload.first
                    val payload = if (protocolsAndPayload.first.isEmpty()) msg.data else protocolsAndPayload.second
                    processStreamData(ctx, currentStream, protocols, payload)
                }
                MplexFlags.ResetReceiver -> {
                    processResetStream(ctx, currentStream)
                }
                MplexFlags.CloseReceiver -> {
                    processCloseStream(ctx, currentStream)
                }
                else -> {
                    println("*** WARN: Unsupported stream flags: $msg")
                    resetStream(ctx, currentStream)
                }
            }
        }

        super.channelRead(ctx, msg)
    }

    /**
     * Processes a newly established stream.
     * @param streamId the stream ID.
     * @param streamName the name of the stream.
     */
    private fun acceptStream(streamId: Long, streamName: String) {
        mapOfStreams[streamId] = MultiplexStream(streamId, false, streamName)
    }

    /**
     * Processes the stream data.
     * @param ctx the channel context.
     * @param stream the stream over which the data was sent.
     * @param protocols the protocols in the payload, if any.
     * @param payload the data payload in the message.
     */
    private fun processStreamData(
        ctx: ChannelHandlerContext,
        stream: MultiplexStream,
        protocols: List<String>,
        payload: ByteArray?
    ) {
        if (!stream.state.canReceive()) {
            resetStream(ctx, stream)
            return
        }
    }

    /**
     * Processes a received request to reset a stream from the other peer.
     * @param ctx the channel context.
     * @param stream the stream to be reset.
     */
    private fun processResetStream(ctx: ChannelHandlerContext, stream: MultiplexStream) {
        if (!stream.state.canReceive()) {
            resetStream(ctx, stream)
            return
        }

        stream.updateState(MultiplexStreamState.RESET_REMOTE)
    }

    /**
     * Processes a received request to close a stream from the other peer.
     * @param ctx the channel context.
     * @param stream the stream to be closed.
     */
    private fun processCloseStream(ctx: ChannelHandlerContext, stream: MultiplexStream) {
        if (!stream.state.canReceive()) {
            resetStream(ctx, stream)
            return
        }
        when (stream.state) {
            MultiplexStreamState.CLOSED_LOCAL -> {
                stream.updateState(MultiplexStreamState.CLOSED_BOTH_WAYS)
            }
            else -> {
                stream.updateState(MultiplexStreamState.CLOSED_REMOTE)
            }
        }
        // TODO: remove from the map.
    }

    /**
     * Resets the given stream.
     * @param ctx the channel context.
     * @param stream the stream to be reset.
     */
    private fun resetStream(ctx: ChannelHandlerContext, stream: MultiplexStream) {
        if (stream.state.canSend()) {
            ctx.writeAndFlush(MplexFrame.createReset(stream.initiator, stream.streamId))
            stream.updateState(MultiplexStreamState.RESET_LOCAL)
            // mapOfStreams.remove(stream.streamId)
        }
    }

    /**
     * Parses the frame's bytes and returns a pair containing the list of protocols in bytes (if any), and the
     * data bytes after the protocol(s).
     * @param bytes the frame's bytes to be parsed.
     * @return a pair containing the list of protocols (if any) and the data payload.
     */
    private fun parseFrame(bytes: ByteArray): Pair<List<String>, ByteArray?> {
        val protocols = mutableListOf<String>()

        var arrayToProcess = bytes
        var parts = arrayToProcess.readUvarint()
        var leftOverBytes: ByteArray? = null

        while (parts != null) {
            val protocolLength = parts.first.toInt()
            val remainingBytes = parts.second
            val protocol = remainingBytes.slice(0 until protocolLength - 1)
            protocols.add(String(protocol.toByteArray()))

            if (remainingBytes.size > parts.first.toInt()) {
                arrayToProcess = remainingBytes.sliceArray(protocolLength until remainingBytes.size)
                parts = arrayToProcess.readUvarint()
                if (parts == null) {
                    // No varint prefix, so remaining bytes must be payload.
                    leftOverBytes = arrayToProcess
                }
            } else {
                parts = null
                leftOverBytes = null
            }
        }

        return Pair(protocols, leftOverBytes)
    }
}