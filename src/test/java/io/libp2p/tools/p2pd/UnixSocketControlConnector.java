package io.libp2p.tools.p2pd;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Created by Anton Nashatyrev on 06.08.2019.
 */
public class UnixSocketControlConnector extends ControlConnector {
    protected static final EventLoopGroup group = new EpollEventLoopGroup();

    public CompletableFuture<DaemonChannelHandler> connect() {
        return connect("/tmp/p2pd.sock");
    }

    public CompletableFuture<DaemonChannelHandler> connect(String socketPath) {
        return connect(new DomainSocketAddress(socketPath));
    }
    public CompletableFuture<DaemonChannelHandler> connect(SocketAddress addr) {
        CompletableFuture<DaemonChannelHandler> ret = new CompletableFuture<>();

        ChannelFuture channelFuture = new Bootstrap()
                .group(group)
                .channel(EpollDomainSocketChannel.class)
                .handler(new ChannelInit(ret::complete, true))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutSec * 1000)
                .connect(addr);

        channelFuture.addListener((ChannelFutureListener) future -> {
            try {
                future.get();
            } catch (Exception e) {
                ret.completeExceptionally(e);
            }
        });
        return ret;
    }

    public ChannelFuture listen(String socketPath, Consumer<DaemonChannelHandler> handlersConsumer) {
        return listen(new DomainSocketAddress(socketPath), handlersConsumer);
    }

    public ChannelFuture listen(SocketAddress addr, Consumer<DaemonChannelHandler> handlersConsumer) {

        return new ServerBootstrap()
                .group(group)
                .channel(EpollServerDomainSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutSec * 1000)
                .childHandler(new ChannelInit(handlersConsumer, false))
                .bind(addr);
    }
}
