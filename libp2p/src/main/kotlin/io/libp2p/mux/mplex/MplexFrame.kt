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

import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toHex
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.MuxFrame
import io.netty.buffer.ByteBuf

/**
 * Contains the fields that comprise an mplex frame.
 * @see [mplex documentation](https://github.com/libp2p/specs/tree/master/mplex#opening-a-new-stream)
 * @param streamId the ID of the stream.
 * @param flag the flag value for this frame.
 * @param data the data segment.
 * @see [mplex documentation](https://github.com/libp2p/specs/tree/master/mplex#opening-a-new-stream)
 */
class MplexFrame(channelId: MuxId, val mplexFlag: Int, data: ByteBuf? = null) :
    MuxFrame(channelId, MplexFlags.toAbstractFlag(mplexFlag), data) {

    override fun toString(): String {
        val init = if (MplexFlags.isInitiator(mplexFlag)) "init" else "resp"
        return "MplexFrame(id=$id, flag=$flag ($init), data=${data?.toByteArray()?.toHex()})"
    }
}
