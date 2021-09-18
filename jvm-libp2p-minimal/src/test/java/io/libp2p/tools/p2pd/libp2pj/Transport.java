package io.libp2p.tools.p2pd.libp2pj;

import io.libp2p.core.multiformats.Multiaddr;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Created by Anton Nashatyrev on 10.12.2018.
 */
public interface Transport extends Connector<Transport.Listener, Multiaddr> {


    @Override
    CompletableFuture<Void> dial(Multiaddr multiaddress,
                                 StreamHandler<Multiaddr> dialHandler);

    @Override
    CompletableFuture<Listener> listen(Multiaddr multiaddress,
                                       Supplier<StreamHandler<Multiaddr>> handlerFactory);

    interface Listener extends Closeable {

        @Override
        void close();

        Multiaddr getLocalMultiaddress();

        default CompletableFuture<Multiaddr> getPublicMultiaddress() {
            return CompletableFuture.completedFuture(getLocalMultiaddress());
        }
    }
}
