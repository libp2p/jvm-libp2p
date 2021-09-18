package io.libp2p.tools.p2pd.libp2pj;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Created by Anton Nashatyrev on 10.12.2018.
 */
public interface Muxer extends Connector<Closeable, Muxer.MuxerAdress> {

    @Override
    CompletableFuture<Void> dial(MuxerAdress muxerAdress, StreamHandler<MuxerAdress> handler);

    @Override
    CompletableFuture<Closeable> listen(MuxerAdress muxerAdress,
                                        Supplier<StreamHandler<MuxerAdress>> handlerFactory);

    public class MuxerAdress {
        public static MuxerAdress listenAddress(String... protocolNames) {
            return new MuxerAdress(null, protocolNames);
        }

        private final List<Protocol> protocols = new ArrayList<>();
        private final Peer peer;

        public MuxerAdress(Peer peer, String... protocolNames) {
            this(Arrays.stream(protocolNames)
                    .map(Protocol::new)
                    .collect(Collectors.toList()), peer);
        }

        public MuxerAdress(List<Protocol> protocols, Peer peer) {
            this.protocols.addAll(protocols);
            this.peer = peer;
        }

        public MuxerAdress(Protocol protocol, Peer peer) {
            this.protocols.add(protocol);
            this.peer = peer;
        }

        public List<Protocol> getProtocols() {
            return protocols;
        }

        public Peer getPeer() {
            return peer;
        }

        @Override
        public String toString() {
            return peer + "[" +
                    protocols.stream().map(Protocol::toString).collect(Collectors.joining(","))
                    + "]";
        }
    }
}
