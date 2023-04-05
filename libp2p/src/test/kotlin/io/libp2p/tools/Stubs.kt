package io.libp2p.tools

import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.etc.util.P2PService
import io.netty.channel.ChannelHandler
import io.netty.channel.EventLoop
import java.util.concurrent.CompletableFuture

class ConnectionStub : Connection {
    override fun muxerSession() = TODO()
    override fun secureSession() = TODO()
    override fun transport() = NullTransport()
    override fun localAddress() = Multiaddr.fromString("/ip4/127.0.0.1/tcp/8888")
    override fun remoteAddress() = Multiaddr.fromString("/ip4/127.0.0.1/tcp/9999")
    override val isInitiator = true
    override fun pushHandler(handler: ChannelHandler) = TODO()
    override fun pushHandler(name: String, handler: ChannelHandler) = TODO()
    override fun addHandlerBefore(baseName: String, name: String, handler: ChannelHandler) = TODO()
    override fun close(): CompletableFuture<Unit> = CompletableFuture.completedFuture(null)
    override fun closeFuture(): CompletableFuture<Unit> = CompletableFuture.completedFuture(null)
}

class StreamStub : Stream {
    private val remotePeerId = PeerId.random()
    override val connection = ConnectionStub()

    override fun eventLoop(): EventLoop {
        TODO("Not yet implemented")
    }

    override fun remotePeerId() = remotePeerId
    override fun getProtocol() = CompletableFuture.completedFuture("nop")
    override fun pushHandler(handler: ChannelHandler) = TODO()
    override fun pushHandler(name: String, handler: ChannelHandler) = TODO()
    override fun writeAndFlush(msg: Any) = TODO()
    override fun close(): CompletableFuture<Unit> = CompletableFuture.completedFuture(null)
    override fun closeWrite(): CompletableFuture<Unit> = CompletableFuture.completedFuture(null)
    override val isInitiator = true
    override fun addHandlerBefore(baseName: String, name: String, handler: ChannelHandler) = TODO()
    override fun closeFuture(): CompletableFuture<Unit> = CompletableFuture.completedFuture(null)
}

fun streamHandlerStub(serviceInstance: P2PService) = serviceInstance.StreamHandler(StreamStub())
fun peerHandlerStub(serviceInstance: P2PService) = serviceInstance.PeerHandler(streamHandlerStub(serviceInstance))
