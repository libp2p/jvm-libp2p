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

import io.libp2p.core.mplex.MplexFrame
import io.libp2p.core.types.readUvarint
import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.writeUvarint
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec

class VarintMessageCodec : MessageToMessageCodec<ByteBuf, MplexFrame>() {
    override fun encode(ctx: ChannelHandlerContext?, msg: MplexFrame, out: MutableList<Any>) {

        val header = msg.streamId.shl(3).or(msg.flag.toLong())
        val lenData = msg.data.size

        val buf = Unpooled.buffer()
        buf.writeUvarint(header)
        buf.writeUvarint(lenData)
        buf.writeBytes(msg.data)
        out.add(buf)

//        out.add(
//            Unpooled.wrappedBuffer(
//                Unpooled.wrappedBuffer(outByteArr),
//                Unpooled.wrappedBuffer(macArr)
//            )
//        )

    }

    override fun decode(ctx: ChannelHandlerContext?, msg: ByteBuf, out: MutableList<Any>) {
        val readableByteCount = msg.readableBytes()
        val current = msg.toByteArray()
        var bytesRead = 0
        var readMoreData = readableByteCount > 0
        while (readMoreData) {
            msg.markReaderIndex()
            val header = msg.readUvarint()
            val streamTag = header.and(0x07).toInt()
            val streamId = header.shr(3)
            val lenData = msg.readUvarint()
            bytesRead = msg.readerIndex()
            if (lenData > readableByteCount - bytesRead) {
                readMoreData = false
                msg.resetReaderIndex()
                out.add(msg)
            } else {
//                val data = msg.readSlice(lenData.toInt()).retain()
                val data = msg.readBytes(lenData.toInt()).toByteArray()
                val frame = MplexFrame(streamTag, streamId, data)
                out.add(frame)
                println("*** Decoded msg=$frame")
                bytesRead = msg.readerIndex()
                readMoreData = bytesRead < readableByteCount
//                out.add(`in`.readSlice(outLen).retain())
            }
        }

        println("*** Done")
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

}