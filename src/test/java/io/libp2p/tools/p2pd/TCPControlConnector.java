package io.libp2p.tools.p2pd;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Created by Anton Nashatyrev on 06.08.2019.
 */
public class TCPControlConnector extends ControlConnector {
    static NioEventLoopGroup group = new NioEventLoopGroup();

    public CompletableFuture<DaemonChannelHandler> connect(String host, int port) {
        return connect(new InetSocketAddress(host, port));
    }
    public CompletableFuture<DaemonChannelHandler> connect(SocketAddress addr) {
        CompletableFuture<DaemonChannelHandler> ret = new CompletableFuture<>();

        ChannelFuture channelFuture = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
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

    public ChannelFuture listen(SocketAddress addr, Consumer<DaemonChannelHandler> handlersConsumer) {
        return new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutSec * 1000)
                .childHandler(new ChannelInit(handlersConsumer, false))
                .bind(addr);
    }
}
