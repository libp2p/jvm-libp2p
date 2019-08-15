package io.libp2p.tools.p2pd.libp2pj;

import java.nio.ByteBuffer;

/**
 * Created by Anton Nashatyrev on 18.12.2018.
 */
public interface StreamHandler<TEndpoint> {

    void onCreate(Stream<TEndpoint> stream);

    void onRead(ByteBuffer data);

    void onClose();

    void onError(Throwable error);
}
