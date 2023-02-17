package io.libp2p.tools.p2pd;

import io.netty.channel.unix.DomainSocketAddress;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Created by Anton Nashatyrev on 20.12.2018.
 */
public class AsyncDaemonExecutor {
    private final SocketAddress address;

    public AsyncDaemonExecutor(SocketAddress address) {
        this.address = address;
    }

    public <TRet> CompletableFuture<TRet> executeWithDaemon(
            Function<DaemonChannelHandler, CompletableFuture<TRet>> executor) {
        CompletableFuture<DaemonChannelHandler> daemonFut = getDaemon();
        return daemonFut
                .thenCompose(executor)
                .whenComplete((r, t) -> {
                    if (!daemonFut.isCompletedExceptionally()) {
                        try {
                            daemonFut.get().close();
                        } catch (Exception e) {}
                    }
                });
    }

    public CompletableFuture<DaemonChannelHandler> getDaemon() {
        ControlConnector connector;
        if (address instanceof InetSocketAddress) {
            connector = new TCPControlConnector();
        } else if (address instanceof DomainSocketAddress) {
            connector = new UnixSocketControlConnector();
        } else {
            throw new IllegalArgumentException();
        }

        return connector.connect(address);
    }

    public SocketAddress getAddress() {
        return address;
    }
}
