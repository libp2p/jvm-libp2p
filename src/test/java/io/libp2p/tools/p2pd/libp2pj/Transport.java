package io.libp2p.tools.p2pd.libp2pj;

import io.ipfs.multiaddr.MultiAddress;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Created by Anton Nashatyrev on 10.12.2018.
 */
public interface Transport extends Connector<Transport.Listener, MultiAddress> {


    @Override
    CompletableFuture<Void> dial(MultiAddress multiaddress,
                                 StreamHandler<MultiAddress> dialHandler);

    @Override
    CompletableFuture<Listener> listen(MultiAddress multiaddress,
                                       Supplier<StreamHandler<MultiAddress>> handlerFactory);

    interface Listener extends Closeable {

        @Override
        void close();

        MultiAddress getLocalMultiaddress();

        default CompletableFuture<MultiAddress> getPublicMultiaddress() {
            return CompletableFuture.completedFuture(getLocalMultiaddress());
        }
    }

}
