package io.libp2p.tools.p2pd;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Created by Anton Nashatyrev on 13.12.2018.
 */
public abstract class ControlConnector {
    protected final int connectTimeoutSec = 5;

    public abstract CompletableFuture<DaemonChannelHandler> connect(SocketAddress addr);

    public abstract ChannelFuture listen(SocketAddress addr, Consumer<DaemonChannelHandler> handlersConsumer);

    protected static class ChannelInit extends ChannelInitializer<Channel> {
        private final Consumer<DaemonChannelHandler> handlersConsumer;
        private final boolean initiator;

        public ChannelInit(Consumer<DaemonChannelHandler> handlersConsumer, boolean initiator) {
            this.handlersConsumer = handlersConsumer;
            this.initiator = initiator;
        }

        @Override
        protected void initChannel(Channel ch) throws Exception {
            DaemonChannelHandler handler = new DaemonChannelHandler(ch, initiator);
            ch.pipeline().addFirst(new SimpleChannelInboundHandler() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                    handlersConsumer.accept(handler);
                    super.channelActive(ctx);
                }

                @Override
                protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
                    handler.onData((ByteBuf) msg);
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                    handler.onError(cause);
                }
            });
        }
    }
}
