package io.libp2p.protocol

import com.google.protobuf.MessageLite
import io.libp2p.core.Stream
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender

abstract class ProtobufProtocolHandler<TController>(
    private val protobufMessagePrototype: MessageLite,
    initiatorTrafficLimit: Long,
    responderTrafficLimit: Long
) : ProtocolHandler<TController>(initiatorTrafficLimit, responderTrafficLimit) {

    override fun initProtocolStream(stream: Stream) {
        with(stream) {
            pushHandler(ProtobufVarint32FrameDecoder())
            pushHandler(ProtobufVarint32LengthFieldPrepender())
            pushHandler(ProtobufDecoder(protobufMessagePrototype))
            pushHandler(ProtobufEncoder())
        }
    }
}
