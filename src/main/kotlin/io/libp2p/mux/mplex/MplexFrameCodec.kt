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

import io.libp2p.core.ProtocolViolationException
import io.libp2p.etc.types.readUvarint
import io.libp2p.etc.types.writeUvarint
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.MuxFrame
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec

const val MaxMessageSize = 1 shl 20

/**
 * A Netty codec implementation that converts [MplexFrame] instances to [ByteBuf] and vice-versa.
 */
class MplexFrameCodec : ByteToMessageCodec<MuxFrame>() {

    /**
     * Encodes the given mplex frame into bytes and writes them into the output list.
     * @see [https://github.com/libp2p/specs/tree/master/mplex]
     * @param ctx the context.
     * @param msg the mplex frame.
     * @param out the list to write the bytes to.
     */
    override fun encode(ctx: ChannelHandlerContext, msg: MuxFrame, out: ByteBuf) {
        out.writeUvarint(msg.id.id.shl(3).or(MplexFlags.toMplexFlag(msg.flag, msg.id.initiator).toLong()))
        out.writeUvarint(msg.data?.readableBytes() ?: 0)
        out.writeBytes(msg.data ?: Unpooled.EMPTY_BUFFER)
    }

    /**
     * Decodes the bytes in the given byte buffer and constructs a [MplexFrame] that is written into
     * the output list.
     * @param ctx the context.
     * @param msg the byte buffer.
     * @param out the list to write the extracted frame to.
     */
    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        while (msg.isReadable) {
            val readerIndex = msg.readerIndex()
            val header = msg.readUvarint()
            val lenData = msg.readUvarint()
            if (header < 0 || lenData < 0 || msg.readableBytes() < lenData) {
                // not enough data to read the frame
                // will wait for more ...
                msg.readerIndex(readerIndex)
                return
            }
            if (lenData > MaxMessageSize) {
                throw ProtocolViolationException("Mplex frame is too large: $lenData")
            }
            val streamTag = header.and(0x07).toInt()
            val streamId = header.shr(3)
            val data = msg.readSlice(lenData.toInt())
            data.retain() // MessageToMessageCodec releases original buffer, but it needs to be relayed
            val initiator = if (streamTag == MplexFlags.NewStream) false else !MplexFlags.isInitiator(streamTag)
            val mplexFrame = MplexFrame(MuxId(ctx.channel().id(), streamId, initiator), streamTag, data)
            out.add(mplexFrame)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // notify higher level handlers on the error
        ctx.fireExceptionCaught(cause)
        // exceptions in [decode] are very likely unrecoverable so just close the connection
        ctx.close()
    }
}
