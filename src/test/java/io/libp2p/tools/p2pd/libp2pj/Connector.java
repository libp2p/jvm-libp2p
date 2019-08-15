package io.libp2p.tools.p2pd.libp2pj;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Created by Anton Nashatyrev on 18.12.2018.
 */
public interface Connector<TListener extends Closeable, TEndpoint> {

    CompletableFuture<Void> dial(TEndpoint address,
                                 StreamHandler<TEndpoint> handler);

    CompletableFuture<TListener> listen(TEndpoint listenAddress,
                                        Supplier<StreamHandler<TEndpoint>> handlerFactory);
}
