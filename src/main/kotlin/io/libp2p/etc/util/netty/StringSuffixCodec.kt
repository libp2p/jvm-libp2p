package io.libp2p.etc.util.netty

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec
import io.netty.handler.codec.MessageToMessageDecoder
import io.netty.handler.codec.MessageToMessageEncoder

/**
 * Adds/removes trailing character from messages
 */
class StringSuffixCodec(val trainlingChar: Char) : MessageToMessageCodec<String, String>() {

    override fun encode(ctx: ChannelHandlerContext?, msg: String, out: MutableList<Any>) {
        out += (msg + trainlingChar)
    }

    override fun decode(ctx: ChannelHandlerContext?, msg: String, out: MutableList<Any>) {
        out += msg.trimEnd(trainlingChar)
    }
}

class StringSuffixEncoder(val trainlingChar: Char) : MessageToMessageEncoder<String>() {
    override fun encode(ctx: ChannelHandlerContext, msg: String, out: MutableList<Any>) {
        out += (msg + trainlingChar)
    }
}

class StringSuffixDecoder(val trainlingChar: Char) : MessageToMessageDecoder<String>() {
    override fun decode(ctx: ChannelHandlerContext?, msg: String, out: MutableList<Any>) {
        out += msg.trimEnd(trainlingChar)
    }
}
