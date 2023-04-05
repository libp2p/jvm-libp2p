package io.libp2p.tools.p2pd.libp2pj;

import io.netty.channel.*;

import java.nio.ByteBuffer;

/**
 * Created by Anton Nashatyrev on 18.12.2018.
 */
public interface Stream<TEndpoint> {

    boolean isInitiator();

    EventLoop eventLoop();

    void write(ByteBuffer data);

    void flush();

    void close();

    TEndpoint getRemoteAddress();

    TEndpoint getLocalAddress();
}
