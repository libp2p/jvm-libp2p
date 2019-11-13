package io.libp2p.tools.p2pd;

import com.google.protobuf.ByteString;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.tools.p2pd.libp2pj.DHT;
import io.libp2p.tools.p2pd.libp2pj.Host;
import io.libp2p.tools.p2pd.libp2pj.Peer;
import io.libp2p.tools.p2pd.libp2pj.Protocol;
import io.libp2p.tools.p2pd.libp2pj.StreamHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.unix.DomainSocketAddress;
import p2pd.pb.P2Pd;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Created by Anton Nashatyrev on 18.12.2018.
 */
public class P2PDHost implements Host {
    private AsyncDaemonExecutor daemonExecutor;

    private final int requestTimeoutSec = 5;

    public static P2PDHost createDefaultDomainSocket() {
        return new P2PDHost(new DomainSocketAddress("/tmp/p2pd.sock"));
    }

    public P2PDHost(SocketAddress addr) {
        daemonExecutor = new AsyncDaemonExecutor(addr);
    }

    @Override
    public DHT getDht() {
        return new P2PDDht(daemonExecutor);
    }

    public P2PDPubsub getPubsub() {
        return new P2PDPubsub(daemonExecutor);
    }

    @Override
    public Peer getMyId() {
        try {
            return new Peer(identify().get().getId().toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Multiaddr> getListenAddresses() {
        try {
            return identify().get().getAddrsList().stream()
                    .map(bs -> new Multiaddr(bs.toByteArray()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private CompletableFuture<P2Pd.IdentifyResponse> identify() {
        return daemonExecutor.executeWithDaemon(h ->
            h.call(P2Pd.Request.newBuilder()
                    .setType(P2Pd.Request.Type.IDENTIFY)
                    .build(), new DaemonChannelHandler.SimpleResponseBuilder())
                    .thenApply(P2Pd.Response::getIdentify)
        );
    }

    @Override
    public CompletableFuture<Void> connect(List<Multiaddr> peerAddresses, Peer peerId) {
        return daemonExecutor.executeWithDaemon(handler -> {
            CompletableFuture<P2Pd.Response> resp = handler.call(P2Pd.Request.newBuilder()
                            .setType(P2Pd.Request.Type.CONNECT)
                            .setConnect(P2Pd.ConnectRequest.newBuilder()
                                    .setPeer(ByteString.copyFrom(peerId.getIdBytes()))
                                    .addAllAddrs(peerAddresses.stream()
                                                    .map(addr -> ByteString.copyFrom(addr.getBytes()))
                                                    .collect(Collectors.toList()))
                                    .setTimeout(requestTimeoutSec)
                                    .build()
                            ).build(),
                    new DaemonChannelHandler.SimpleResponseBuilder());
            return resp.thenApply(r -> null);
        });
    }

    private final List<Closeable> activeChannels = new Vector<>();
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public CompletableFuture<Void> dial(MuxerAdress muxerAdress, StreamHandler<MuxerAdress> streamHandler) {
        try {
            return daemonExecutor.getDaemon().thenCompose(handler -> {
                try {
                    handler.setStreamHandler(new StreamHandlerWrapper<>(streamHandler)
                            .onCreate(s -> activeChannels.add(handler))
                            .onClose(() -> activeChannels.remove(handler))
                    );
                    CompletableFuture<P2Pd.Response> resp = handler.call(P2Pd.Request.newBuilder()
                                    .setType(P2Pd.Request.Type.STREAM_OPEN)
                                    .setStreamOpen(P2Pd.StreamOpenRequest.newBuilder()
                                            .setPeer(ByteString.copyFrom(muxerAdress.getPeer().getIdBytes()))
                                            .addAllProto(muxerAdress.getProtocols().stream()
                                                    .map(Protocol::getName).collect(Collectors.toList()))
                                            .setTimeout(requestTimeoutSec)
                                            .build()
                                    ).build(),
                            new DaemonChannelHandler.SimpleResponseStreamBuilder());
                    return resp.whenComplete((r, t) -> {
                        if (t != null) {
                            streamHandler.onError(t);
                            handler.close();
                        }
                    }).thenApply(r -> null);
                } catch (Exception e) {
                    handler.close();
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Closeable> listen(MuxerAdress muxerAdress, Supplier<StreamHandler<MuxerAdress>> handlerFactory) {
        Multiaddr listenMultiaddr;
        SocketAddress listenAddr;
        ControlConnector connector;
        if (daemonExecutor.getAddress() instanceof InetSocketAddress) {
            connector = new TCPControlConnector();
            int port = 46666 + counter.incrementAndGet();
            listenAddr = new InetSocketAddress("127.0.0.1", port);
            listenMultiaddr = new Multiaddr("/ip4/127.0.0.1/tcp/46666");
        } else if (daemonExecutor.getAddress() instanceof DomainSocketAddress) {
            connector = new UnixSocketControlConnector();
            String path = "/tmp/p2pd.client." + counter.incrementAndGet();
            listenAddr = new DomainSocketAddress(path);
            listenMultiaddr = new Multiaddr("/unix" + path);
        } else {
            throw new IllegalStateException();
        }

        ChannelFuture channelFuture = connector.listen(listenAddr, h -> {
            StreamHandler<MuxerAdress> streamHandler = handlerFactory.get();
            h.setStreamHandler(streamHandler);
            CompletableFuture<P2Pd.StreamInfo> response = h.expectResponse(new DaemonChannelHandler.ListenerStreamBuilder());
            response.whenComplete((r, t) -> {
                if (t != null) {
                    streamHandler.onError(t);
                }
            });
        });

        channelFuture.addListener((ChannelFutureListener)
                future -> activeChannels.add(() -> future.channel().close()));

        Closeable ret = () -> channelFuture.channel().close();
        return Util.channelFutureToJava(channelFuture)
                .thenCompose(channel ->
                        daemonExecutor.executeWithDaemon(handler ->
                            handler.call(P2Pd.Request.newBuilder()
                                        .setType(P2Pd.Request.Type.STREAM_HANDLER)
                                        .setStreamHandler(P2Pd.StreamHandlerRequest.newBuilder()
                                            .setAddr(ByteString.copyFrom(listenMultiaddr.getBytes()))
                                            .addAllProto(muxerAdress.getProtocols().stream()
                                                .map(Protocol::getName).collect(Collectors.toList()))
                                            .build()
                                        ).build(),
                                    new DaemonChannelHandler.SimpleResponseBuilder())))
                .thenApply(resp1 -> ret);
    }

    @Override
    public void close() {
        activeChannels.forEach(ch -> {
            try {
                ch.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
