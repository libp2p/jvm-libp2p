package io.libp2p.pubsub;

import io.libp2p.pubsub.gossip.GossipParams;
import io.libp2p.pubsub.gossip.GossipParamsKt;
import io.libp2p.pubsub.gossip.GossipRouter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pubsub.pb.Rpc;

public class GossipApiTest {

    @Test
    public void createGossipTest() {
        GossipParams gossipParams = GossipParams.builder()
                .D(10)
                .DHigh(20)
                .build();
        GossipRouter router = new GossipRouter(gossipParams);
        router.setMessageIdGenerator(m -> "Hey!");

        Assertions.assertEquals("Hey!", router.getMessageId(Rpc.Message.getDefaultInstance()));
        Assertions.assertEquals(10, router.getParams().getD());
        Assertions.assertEquals(20, router.getParams().getDHigh());
        Assertions.assertEquals(GossipParamsKt.defaultDScore(10), router.getParams().getDScore());
    }
}
