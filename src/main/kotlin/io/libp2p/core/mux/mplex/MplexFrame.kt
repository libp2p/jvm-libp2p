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

import io.libp2p.core.mplex.MplexFlags
import io.libp2p.core.mux.MultistreamFrame
import io.libp2p.core.types.writeUvarint
import io.libp2p.core.util.netty.multiplex.MultiplexId
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

/**
 * Contains the fields that comprise an mplex frame.
 * @see [mplex documentation](https://github.com/libp2p/specs/tree/master/mplex#opening-a-new-stream)
 * @param streamId the ID of the stream.
 * @param flag the flag value for this frame.
 * @param data the data segment.
 * @see [mplex documentation](https://github.com/libp2p/specs/tree/master/mplex#opening-a-new-stream)
 */
class MplexFrame(streamId: Long, val mplexFlag: Int, data: ByteBuf? = null)
    : MultistreamFrame(MultiplexId(streamId), MplexFlags.toAbstractFlag(mplexFlag), data){

    companion object {

        /**
         * Separates data items in a frame.
         */
        private const val ItemSeparator = '\n'

        /**
         * Creates a frame representing a new stream with the given ID and (optional) name.
         * @param streamId the stream ID.
         * @param MustreamName the optional name of the stream.
         * @return the frame.
         */
        fun createNewStream(streamId: Long, streamName: String = "$streamId"): MplexFrame {
            return MplexFrame(
                streamId,
                MplexFlags.NewStream,
                createStreamData(streamName)
            )
        }

        /**
         * Creates a frame representing a message to be conveyed to the other peer.
         * @param initiator whether this peer is the initiator.
         * @param streamId the stream ID.
         * @param strings an array of string values to be sent to the other peer.
         * @return the frame.
         */
        fun createMessage(initiator: Boolean, streamId: Long, vararg strings: String): MplexFrame {
            return MplexFrame(
                streamId,
                if (initiator) MplexFlags.MessageInitiator else MplexFlags.MessageReceiver,
                createStreamData(*strings)
            )
        }

        /**
         * Creates a frame representing a reset message to be conveyed to the other peer.
         * @param initiator whether htis peer is the initiator.
         * @param streamId the stream ID.
         * @return the frame.
         */
        fun createReset(initiator: Boolean, streamId: Long): MplexFrame {
            return MplexFrame(
                streamId,
                if (initiator) MplexFlags.ResetInitiator else MplexFlags.ResetReceiver
            )
        }

        /**
         * Converts the given strings into the correct payload data structure to be written out in an [MplexFrame].
         * @param strings string to be written out in the payload.
         * @return a byte array representing the payload.
         */
        private fun createStreamData(vararg strings: String): ByteBuf {
            return Unpooled.buffer().apply {
                strings.forEach {
                    // Add a '\n' char if it doesn't already end with one.
                    val stringToWrite =
                        if (it.endsWith(ItemSeparator)) {
                            it
                        } else {
                            it + ItemSeparator
                        }
                    // Write each string with the length prefix.
                    writeUvarint(stringToWrite.length)
                    writeBytes(stringToWrite.toByteArray())
                }
            }
        }
    }
}
