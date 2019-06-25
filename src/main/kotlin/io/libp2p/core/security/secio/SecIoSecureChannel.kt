package io.libp2p.core.security.secio

import io.libp2p.core.PeerId
import io.libp2p.core.events.SecureChannelFailed
import io.libp2p.core.events.SecureChannelInitialized
import io.libp2p.core.protocol.Mode
import io.libp2p.core.protocol.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import java.security.PublicKey
import java.util.concurrent.CompletableFuture

/**
 * Integrates the SecIO secure channel with libp2p.
 */
class SecioSecureChannel : SecureChannel {
    override val announce = "/secio/1.0.0"
    override val matcher = ProtocolMatcher(Mode.STRICT, name = "/secio/1.0.0")

    /**
     * Activates the SecIO secure channel on this Channel, returning a promise for a secure session.
     */
    override fun activate(ch: Channel): CompletableFuture<SecureChannel.Session> {
        val ret = CompletableFuture<SecureChannel.Session>()

        // bridge the result of the secure channel bootstrap with the promise.
        ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
            override fun userEventTriggered(ctx: ChannelHandlerContext?, evt: Any?) {
                when(evt) {
                    is SecureChannelInitialized -> ret.complete(evt.session)
                    is SecureChannelFailed -> ret.completeExceptionally(evt.exception)
                }
            }
        })

        // this handler is the entrypoint to SecIO.
        ch.pipeline().addLast(SecioHandler(ch))
        return ret
    }

    /**
     * SecioHandler bootstraps SecIO on this channel. It MUST signal completion by one of two end actions:
     *
     *   * Firing the SecureChannelInitialized user-triggered event on the channel, _after_ setting the SECURE_SESSION
     *     attribute on the channe.
     *   * Firing the SecureChannelFailed user-triggered event on the channel, if setting up the secure channel failed.
     *     This includes timeouts.
     */
    class SecioHandler(val ch: Channel): ChannelInboundHandlerAdapter()

    /**
     * SecioSession exposes the identity and public security material of the other party as authenticated by SecIO.
     */
    class SecioSession(
        override val localId: PeerId,
        override val remoteId: PeerId,
        override val remotePubKey: PublicKey
    ) : SecureChannel.Session

}