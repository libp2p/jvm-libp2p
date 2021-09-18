package io.libp2p.tools.p2pd;

import com.google.protobuf.ByteString;
import p2pd.pb.P2Pd;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;

/**
 * Created by Anton Nashatyrev on 20.12.2018.
 */
public class P2PDPubsub {
    private final AsyncDaemonExecutor daemonExecutor;

    public P2PDPubsub(AsyncDaemonExecutor daemonExecutor) {
        this.daemonExecutor = daemonExecutor;
    }

    public CompletableFuture<BlockingQueue<P2Pd.PSMessage>> subscribe(String topic) {
        return daemonExecutor.getDaemon().thenCompose(h -> {
            return h.call(
                    newPubsubRequest(P2Pd.PSRequest.newBuilder()
                            .setType(P2Pd.PSRequest.Type.SUBSCRIBE)
                            .setTopic(topic))
                    , new DaemonChannelHandler.UnboundMessagesResponse<>(
                            is -> {
                                try {
                                    return P2Pd.PSMessage.parseDelimitedFrom(is);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                    ));
        });
    }

    public CompletableFuture<Void> publish(String topic, byte[] data) {
        return daemonExecutor.executeWithDaemon(h -> {
            CompletableFuture<P2Pd.Response> resp = h.call(
                    newPubsubRequest(P2Pd.PSRequest.newBuilder()
                            .setType(P2Pd.PSRequest.Type.PUBLISH)
                            .setTopic(topic)
                            .setData(ByteString.copyFrom(data)))
                    , new DaemonChannelHandler.SimpleResponseBuilder());
            return resp.thenApply(r -> null);
        });
    }

    private static P2Pd.Request newPubsubRequest(P2Pd.PSRequest.Builder pubsub) {
        return P2Pd.Request.newBuilder()
                .setType(P2Pd.Request.Type.PUBSUB)
                .setPubsub(pubsub)
                .build();
    }
}
