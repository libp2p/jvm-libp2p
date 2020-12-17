package io.libp2p.protocol

import com.google.protobuf.MessageLite
import io.libp2p.core.P2PChannel
import io.libp2p.core.Stream
import io.libp2p.etc.util.netty.protobuf.LimitedProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import java.util.concurrent.CompletableFuture

abstract class ProtobufProtocolHandler<out TController>(
    private val protobufMessagePrototype: MessageLite
) : ProtocolHandler<TController>() {
    private val maxMsgSize = 1 shl 13

    override fun initChannel(ch: P2PChannel): CompletableFuture<out TController> {
        val stream = ch as Stream

        with(stream) {
            pushHandler(LimitedProtobufVarint32FrameDecoder(maxMsgSize))
            pushHandler(ProtobufVarint32LengthFieldPrepender())
            pushHandler(ProtobufDecoder(protobufMessagePrototype))
            pushHandler(ProtobufEncoder())
        }

        return super.initChannel(ch)
    }
}
