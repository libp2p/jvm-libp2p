package io.libp2p.core

import com.google.protobuf.MessageLite
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.protocol.ProtocolMessageHandlerAdapter
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import java.lang.reflect.ParameterizedType
import java.util.concurrent.CompletableFuture

/**
 * Represents a multiplexed stream over wire connection
 */
interface Stream : P2PChannel {
    val connection: Connection

    /**
     * Returns the [PeerId] of the remote peer [Connection] which this
     * [Stream] created on
     */
    fun remotePeerId(): PeerId

    /**
     * @return negotiated protocol
     */
    fun getProtocol(): CompletableFuture<String>

    fun <TMessage> pushHandler(protocolHandler: ProtocolMessageHandler<TMessage>) {
        val protobufClass = protobufMessageType(protocolHandler)
        if (protobufClass != null) {
            pushHandler(ProtobufVarint32FrameDecoder())
            pushHandler(ProtobufVarint32LengthFieldPrepender())
            pushHandler(ProtobufDecoder(protobufMessageInstance(protobufClass)))
            pushHandler(ProtobufEncoder())
        }
        pushHandler(ProtocolMessageHandlerAdapter(this, protocolHandler))
    }

    fun writeAndFlush(msg: Any)

    private fun protobufMessageType(candidate: Any): Class<*>? {
        val candidateClass = if (candidate is Class<*>) candidate else candidate.javaClass
        val interfaces = candidateClass.genericInterfaces
        for (type in interfaces) {
            if (type !is ParameterizedType) {
                val mT = protobufMessageType(type)
                if (mT != null)
                    return mT
                else
                    continue
            }
            val typeParameter = type.actualTypeArguments.first() as Class<*>
            val protobufClass = MessageLite::class.java

            val isProtobuf = protobufClass.isAssignableFrom(typeParameter)
            if (isProtobuf)
                return typeParameter
        }
        return null
    } // protobufMessageType

    private fun protobufMessageInstance(type: Class<*>): MessageLite {
        val factoryMethod = type.getMethod("getDefaultInstance")
        val instance = factoryMethod.invoke(null)
        return instance as MessageLite
    }
}
