package io.libp2p.tools.p2pd.libp2pj;

import java.nio.ByteBuffer;

/**
 * Created by Anton Nashatyrev on 18.12.2018.
 */
public interface Stream<TEndpoint> {

    boolean isInitiator();

    void write(ByteBuffer data);

    void flush();

    void close();

    TEndpoint getRemoteAddress();

    TEndpoint getLocalAddress();
}
