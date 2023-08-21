package io.libp2p.pubsub;

import com.google.protobuf.ByteString;
import io.libp2p.core.pubsub.ValidationResult;
import io.libp2p.etc.types.WBytes;
import io.libp2p.etc.util.P2PService;
import io.libp2p.pubsub.gossip.GossipParams;
import io.libp2p.pubsub.gossip.GossipParamsKt;
import io.libp2p.pubsub.gossip.GossipRouter;
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pubsub.pb.Rpc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static io.libp2p.tools.StubsKt.peerHandlerStub;
import static org.assertj.core.api.Assertions.assertThat;

public class GossipApiTest {

    @Test
    public void createGossipTest() {
        GossipParams gossipParams = GossipParams.builder()
                .D(10)
                .DHigh(20)
                .build();
        GossipRouterBuilder routerBuilder = new GossipRouterBuilder();
        routerBuilder.setParams(gossipParams);
        GossipRouter router = routerBuilder.build();
        assertThat(router.getParams().getD()).isEqualTo(10);
        assertThat(router.getParams().getDHigh()).isEqualTo(20);
        assertThat(router.getParams().getDScore()).isEqualTo(GossipParamsKt.defaultDScore(10));
    }

    @Test
    public void testFastMessageId() throws Exception {
        List<TestPubsubMessage> createdMessages = new ArrayList<>();

        GossipRouterBuilder routerBuilder = new GossipRouterBuilder();
        routerBuilder.setSeenCache(new FastIdSeenCache<>(msg -> msg.getProtobufMessage().getData()));
        routerBuilder.setMessageFactory(m -> {
            TestPubsubMessage message = new TestPubsubMessage(m);
            createdMessages.add(message);
            return message;
        });
        GossipRouter router = routerBuilder.build();
        router.subscribe("topic");

        BlockingQueue<PubsubMessage> messages = new LinkedBlockingQueue<>();
        router.initHandler(m -> {
            messages.add(m);
            return CompletableFuture.completedFuture(ValidationResult.Valid);
        });

        P2PService.PeerHandler peerHandler = peerHandlerStub(router);

        router.runOnEventThread(() -> router.onInbound(peerHandler, newMessage("Hello-1")));
        TestPubsubMessage message1 = (TestPubsubMessage) messages.poll(1, TimeUnit.SECONDS);

        assertThat(message1).isNotNull();
        assertThat(message1.canonicalId).isNotNull();
        assertThat(createdMessages.size()).isEqualTo(1);
        createdMessages.clear();

        router.runOnEventThread(() -> router.onInbound(peerHandler, newMessage("Hello-1")));
        TestPubsubMessage message2 = (TestPubsubMessage) messages.poll(100, TimeUnit.MILLISECONDS);

        assertThat(message2).isNull();
        assertThat(createdMessages.size()).isEqualTo(1);
        // assert that 'slow' canonicalId was not calculated and the message was filtered as seen by fastId
        assertThat(createdMessages.get(0).canonicalId).isNull();
        createdMessages.clear();
    }

    private static Rpc.RPC newMessage(String msg) {
        return Rpc.RPC.newBuilder().addPublish(
                Rpc.Message.newBuilder()
                        .addTopicIDs("topic")
                        .setData(ByteString.copyFrom(msg, StandardCharsets.US_ASCII))
        ).build();
    }

    private static class TestPubsubMessage implements PubsubMessage {
        final Rpc.Message message;
        Function<Rpc.Message, WBytes> canonicalIdCalculator = m -> new WBytes(("canon-" + m.getData().toString()).getBytes());
        WBytes canonicalId = null;

        public TestPubsubMessage(Rpc.Message message) {
            this.message = message;
        }

        @NotNull
        @Override
        public Rpc.Message getProtobufMessage() {
            return message;
        }

        @NotNull
        @Override
        public WBytes getMessageId() {
            if (canonicalId == null) {
                canonicalId = canonicalIdCalculator.apply(getProtobufMessage());
            }
            return canonicalId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestPubsubMessage that = (TestPubsubMessage) o;
            return message.equals(that.message);
        }

        @Override
        public int hashCode() {
            return message.hashCode();
        }
    }

}
