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

import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.writeUvarint
import io.netty.buffer.Unpooled

/**
 * Contains the fields that comprise an mplex frame.
 * @see [mplex documentation](https://github.com/libp2p/specs/tree/master/mplex#opening-a-new-stream)
 * @param streamId the ID of the stream.
 * @param flag the flag value for this frame.
 * @param data the data segment.
 */
data class MplexFrame(val streamId: Long, val flag: Int, val data: ByteArray) {

    val dataString = String(data)

    override fun toString(): String {
        return "[$streamId]: tag=$streamTag, len(data)=${data.size}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MplexFrame

        if (streamId != other.streamId) return false
        if (flag != other.flag) return false
        if (!data.contentEquals(other.data)) return false
        if (dataString != other.dataString) return false

        return true
    }

    override fun hashCode(): Int {
        var result = streamId.hashCode()
        result = 31 * result + flag
        result = 31 * result + data.contentHashCode()
        result = 31 * result + dataString.hashCode()
        return result
    }


//    var data = createStreamStrings("/multistream/1.0.0\n", "/chat/1.0.0\n")
//    var frame = MplexFrame(MplexDataHandler.messageTag + 1, msg.streamId, data)

    companion object {

        fun createStream(streamId: Long, streamName: String = "$streamId"): MplexFrame {
            return MplexFrame(MplexDataHandler.NewStream, streamId, createStreamStrings(streamName))
        }

        fun createMessage(initiator: Boolean, streamId: Long, vararg strings: String): MplexFrame {
            val tag = if (initiator) MplexDataHandler.MessageInitiator else MplexDataHandler.MessageReceiver
            return MplexFrame(tag, streamId, createStreamStrings(*strings))
        }

        private fun createStreamStrings(vararg strings: String): ByteArray {
            return with(Unpooled.buffer()) {
                strings.forEach {
                    writeUvarint(it.length)
                    writeBytes(it.toByteArray())
                }
                toByteArray()
            }
        }

    }
}
