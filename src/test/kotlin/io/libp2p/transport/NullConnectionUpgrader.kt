package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.StreamHandler
import io.libp2p.core.StreamPromise
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import java.util.concurrent.CompletableFuture

class NullConnectionUpgrader : ConnectionUpgrader(emptyList(), emptyList()) {

    override fun establishSecureChannel(connection: Connection):
        CompletableFuture<SecureChannel.Session> {
            val nonsenseSession = SecureChannel.Session(
                PeerId.random(),
                PeerId.random(),
                generateKeyPair(KEY_TYPE.RSA).second
            )
            return CompletableFuture.completedFuture(nonsenseSession)
        } // establishSecureChannel

    override fun establishMuxer(connection: Connection):
        CompletableFuture<StreamMuxer.Session> {
            return CompletableFuture.completedFuture(DoNothingMuxerSession())
        } // establishMuxer

    private class DoNothingMuxerSession : StreamMuxer.Session {
        override var inboundStreamHandler: StreamHandler<*>?
            get() = throw NotImplementedError("Test only. Shouldn't be called")
            set(_) {}

        override fun <T> createStream(streamHandler: StreamHandler<T>): StreamPromise<T> {
            throw NotImplementedError("Test only. Shouldn't be called")
        }
    }
} // class NullConnectionUpgrader
