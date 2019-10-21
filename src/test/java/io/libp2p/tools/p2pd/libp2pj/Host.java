package io.libp2p.tools.p2pd.libp2pj;

import io.libp2p.core.multiformats.Multiaddr;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Created by Anton Nashatyrev on 18.12.2018.
 */
public interface Host extends Muxer {

    Peer getMyId();

    List<Multiaddr> getListenAddresses();

    CompletableFuture<Void> connect(List<Multiaddr> peerAddresses, Peer peerId);

    @Override
    CompletableFuture<Void> dial(MuxerAdress muxerAdress, StreamHandler<MuxerAdress> handler);

    @Override
    CompletableFuture<Closeable> listen(MuxerAdress muxerAdress,
                                        Supplier<StreamHandler<MuxerAdress>> handlerFactory);

    void close();

    DHT getDht();

//    Peerstore getPeerStore();

//    Network getNetwork();

//    ConnectionManager getConnectionManager();
}
