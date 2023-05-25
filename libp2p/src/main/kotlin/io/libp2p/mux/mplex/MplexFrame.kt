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
package io.libp2p.mux.mplex

import io.libp2p.etc.util.netty.mux.MuxId
import io.netty.buffer.ByteBuf
import io.netty.buffer.DefaultByteBufHolder
import io.netty.buffer.Unpooled

/**
 * Contains the fields that comprise an mplex frame.
 * @see [mplex documentation](https://github.com/libp2p/specs/tree/master/mplex#opening-a-new-stream)
 * @param streamId the ID of the stream.
 * @param flag the flag value for this frame.
 * @param data the data segment.
 * @see [mplex documentation](https://github.com/libp2p/specs/tree/master/mplex#opening-a-new-stream)
 */
data class MplexFrame(val id: MuxId, val flag: MplexFlag, val data: ByteBuf) : DefaultByteBufHolder(data) {

    companion object {
        fun createDataFrame(id: MuxId, data: ByteBuf) =
            MplexFrame(id, MplexFlag.getByType(MplexFlag.Type.DATA, id.initiator), data)
        fun createOpenFrame(id: MuxId) =
            MplexFrame(id, MplexFlag.getByType(MplexFlag.Type.OPEN, id.initiator), Unpooled.EMPTY_BUFFER)
        fun createCloseFrame(id: MuxId) =
            MplexFrame(id, MplexFlag.getByType(MplexFlag.Type.CLOSE, id.initiator), Unpooled.EMPTY_BUFFER)
        fun createResetFrame(id: MuxId) =
            MplexFrame(id, MplexFlag.getByType(MplexFlag.Type.RESET, id.initiator), Unpooled.EMPTY_BUFFER)
    }
}
