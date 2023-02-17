package io.libp2p.tools.p2pd;

import com.google.protobuf.ByteString;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.tools.p2pd.libp2pj.DHT;
import io.libp2p.tools.p2pd.libp2pj.Peer;
import io.libp2p.tools.p2pd.libp2pj.PeerInfo;
import io.libp2p.tools.p2pd.libp2pj.util.Cid;
import p2pd.pb.P2Pd;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Created by Anton Nashatyrev on 20.12.2018.
 */
public class P2PDDht implements DHT {
    private final AsyncDaemonExecutor daemonExecutor;

    public P2PDDht(AsyncDaemonExecutor daemonExecutor) {
        this.daemonExecutor = daemonExecutor;
    }

    @Override
    public CompletableFuture<PeerInfo> findPeer(Peer peerId) {
        return daemonExecutor.executeWithDaemon(h -> {
            CompletableFuture<P2Pd.Response> resp = h.call(
                    newDhtRequest(P2Pd.DHTRequest.newBuilder()
                        .setType(P2Pd.DHTRequest.Type.FIND_PEER)
                        .setPeer(ByteString.copyFrom(peerId.getIdBytes())))
                    , new DaemonChannelHandler.SimpleResponseBuilder());
            return resp.thenApply(r -> fromResp(r.getDht().getPeer()));
        });
    }

    @Override
    public CompletableFuture<List<PeerInfo>> findPeersConnectedToPeer(Peer peerId) {
        return daemonExecutor.executeWithDaemon(h -> {
            CompletableFuture<List<P2Pd.DHTResponse>> resp = h.call(
                    newDhtRequest(P2Pd.DHTRequest.newBuilder()
                            .setType(P2Pd.DHTRequest.Type.FIND_PEERS_CONNECTED_TO_PEER)
                            .setPeer(ByteString.copyFrom(peerId.getIdBytes())))
                    , new DaemonChannelHandler.DHTListResponse());

            return resp.thenApply(list ->
                list.stream().map(pi -> fromResp(pi.getPeer())).collect(Collectors.toList()));
        });
    }

    @Override
    public CompletableFuture<List<PeerInfo>> findProviders(Cid cid, int maxRetCount) {
        return daemonExecutor.executeWithDaemon(h -> {
            CompletableFuture<List<P2Pd.DHTResponse>> resp = h.call(
                    newDhtRequest(P2Pd.DHTRequest.newBuilder()
                            .setType(P2Pd.DHTRequest.Type.FIND_PROVIDERS)
                            .setCid(ByteString.copyFrom(cid.toBytes())))
                    , new DaemonChannelHandler.DHTListResponse());

            return resp.thenApply(list ->list.stream()
                            .map(pi -> fromResp(pi.getPeer())).collect(Collectors.toList()));
        });
    }

    @Override
    public CompletableFuture<List<PeerInfo>> getClosestPeers(byte[] key) {
        return daemonExecutor.executeWithDaemon(h -> {
            CompletableFuture<List<P2Pd.DHTResponse>> resp = h.call(
                    newDhtRequest(P2Pd.DHTRequest.newBuilder()
                            .setType(P2Pd.DHTRequest.Type.GET_CLOSEST_PEERS)
                            .setKey(ByteString.copyFrom(key)))
                    , new DaemonChannelHandler.DHTListResponse());

            return resp.thenApply(list ->list.stream()
                            .map(pi -> fromResp(pi.getPeer())).collect(Collectors.toList()));
        });
    }

    @Override
    public CompletableFuture<byte[]> getPublicKey(Peer peerId) {
        return daemonExecutor.executeWithDaemon(h -> {
            CompletableFuture<P2Pd.Response> resp = h.call(
                    newDhtRequest(P2Pd.DHTRequest.newBuilder()
                            .setType(P2Pd.DHTRequest.Type.GET_PUBLIC_KEY)
                            .setPeer(ByteString.copyFrom(peerId.getIdBytes())))
                    , new DaemonChannelHandler.SimpleResponseBuilder());
            return resp.thenApply(r -> r.getDht().getValue().toByteArray());
        });
    }

    @Override
    public CompletableFuture<byte[]> getValue(byte[] key) {
        return daemonExecutor.executeWithDaemon(h -> {
            CompletableFuture<P2Pd.Response> resp = h.call(
                    newDhtRequest(P2Pd.DHTRequest.newBuilder()
                            .setType(P2Pd.DHTRequest.Type.GET_VALUE)
                            .setKey(ByteString.copyFrom(key)))
                    , new DaemonChannelHandler.SimpleResponseBuilder());
            return resp.thenApply(r -> r.getDht().getValue().toByteArray());
        });
    }

    @Override
    public CompletableFuture<List<byte[]>> searchValue(byte[] key) {
        return daemonExecutor.executeWithDaemon(h -> {
            CompletableFuture<List<P2Pd.DHTResponse>> resp = h.call(
                    newDhtRequest(P2Pd.DHTRequest.newBuilder()
                            .setType(P2Pd.DHTRequest.Type.SEARCH_VALUE)
                            .setKey(ByteString.copyFrom(key)))
                    , new DaemonChannelHandler.DHTListResponse());

            return resp.thenApply(list ->list.stream()
                    .map(val -> val.getValue().toByteArray()).collect(Collectors.toList()));
        });
    }

    @Override
    public CompletableFuture<Void> putValue(byte[] key, byte[] value) {
        return daemonExecutor.executeWithDaemon(h -> {
            CompletableFuture<P2Pd.Response> resp = h.call(
                    newDhtRequest(P2Pd.DHTRequest.newBuilder()
                            .setType(P2Pd.DHTRequest.Type.PUT_VALUE)
                            .setKey(ByteString.copyFrom(key))
                            .setValue(ByteString.copyFrom(value)))
                    , new DaemonChannelHandler.SimpleResponseBuilder());
            return resp.thenApply(r -> null);
        });
    }

    @Override
    public CompletableFuture<Void> provide(Cid cid) {
        return daemonExecutor.executeWithDaemon(h -> {
            CompletableFuture<P2Pd.Response> resp = h.call(
                    newDhtRequest(P2Pd.DHTRequest.newBuilder()
                            .setType(P2Pd.DHTRequest.Type.PROVIDE)
                            .setCid(ByteString.copyFrom(cid.toBytes())))
                    , new DaemonChannelHandler.SimpleResponseBuilder());
            return resp.thenApply(r -> null);
        });
    }

    private static PeerInfo fromResp(P2Pd.PeerInfo pi) {
        return new PeerInfo(new Peer(pi.getId().toByteArray()),
                pi.getAddrsList().stream()
                        .map(addr -> Multiaddr.deserialize(addr.toByteArray()))
                        .collect(Collectors.toList()));
    }

    private static P2Pd.Request newDhtRequest(P2Pd.DHTRequest.Builder dht) {
        return P2Pd.Request.newBuilder()
                .setType(P2Pd.Request.Type.DHT)
                .setDht(dht)
                .build();
    }
}
