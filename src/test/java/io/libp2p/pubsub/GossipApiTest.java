package io.libp2p.pubsub;

import com.google.protobuf.ByteString;
import io.libp2p.core.pubsub.ValidationResult;
import io.libp2p.etc.util.P2PService;
import io.libp2p.pubsub.gossip.GossipParams;
import io.libp2p.pubsub.gossip.GossipParamsKt;
import io.libp2p.pubsub.gossip.GossipRouter;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pubsub.pb.Rpc;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        GossipRouter router = new GossipRouter(gossipParams);
//        router.setMessageFactory(m -> new PubsubMessage() {
//            @NotNull
//            @Override
//            public Rpc.Message getProtobufMessage() {
//                return null;
//            }
//
//            @NotNull
//            @Override
//            public String getMessageId() {
//                return "Hey";
//            }
//        });
//
//        Assertions.assertEquals("Hey!", router.(Rpc.Message.getDefaultInstance()));
        assertThat(router.getParams().getD()).isEqualTo(10);
        assertThat(router.getParams().getDHigh()).isEqualTo(20);
        assertThat(router.getParams().getDScore()).isEqualTo(GossipParamsKt.defaultDScore(10));
    }

    @Test
    public void testFastMessageId() throws Exception {
        GossipRouter router = new GossipRouter() {
            private final MessageMap<Optional<ValidationResult>> seenMessages = new MessageMap<>();

            @NotNull
            @Override
            protected Map<PubsubMessage, Optional<ValidationResult>> getSeenMessages() {
                return seenMessages;
            }
        };
        List<CustomMessage> createdMessages = new ArrayList<>();
        router.setMessageFactory(m -> {
            CustomMessage message = new CustomMessage(m);
            createdMessages.add(message);
            return message;
        });
        router.subscribe("topic");

        BlockingQueue<PubsubMessage> messages = new LinkedBlockingQueue<>();
        router.initHandler(m -> {
            messages.add(m);
            return CompletableFuture.completedFuture(ValidationResult.Valid);
        });

        P2PService.PeerHandler peerHandler = peerHandlerStub(router);

        router.onInbound(peerHandler, newMessage("Hello-1"));
        CustomMessage message1 = (CustomMessage) messages.poll(1, TimeUnit.SECONDS);

        assertThat(message1).isNotNull();
        assertThat(message1.canonicalId).isNotNull();
        assertThat(createdMessages.size()).isEqualTo(1);
        createdMessages.clear();

        router.onInbound(peerHandler, newMessage("Hello-1"));
        CustomMessage message2 = (CustomMessage) messages.poll(100, TimeUnit.MILLISECONDS);

        assertThat(message2).isNull();
        assertThat(createdMessages.size()).isEqualTo(1);
        // assert that 'slow' canonicalId was not calculated and the message was filtered as seen by fastId
        assertThat(createdMessages.get(0).canonicalId).isNull();
        createdMessages.clear();
    }

    Rpc.RPC newMessage(String msg) {
        return Rpc.RPC.newBuilder().addPublish(
                Rpc.Message.newBuilder()
                        .addTopicIDs("topic")
                        .setData(ByteString.copyFrom("Hello-1", StandardCharsets.US_ASCII))
        ).build();
    }

    static class CustomMessage implements PubsubMessage {
        final Rpc.Message message;
        Function<Rpc.Message, byte[]> canonicalIdCalculator = m -> ("canon-" + m.getData().toString()).getBytes();
        byte[] canonicalId = null;

        public CustomMessage(Rpc.Message message) {
            this.message = message;
        }

        @NotNull
        @Override
        public Rpc.Message getProtobufMessage() {
            return message;
        }

        public Object fastMessageId() {
            return getProtobufMessage().getData();
        }

        @NotNull
        @Override
        public byte[] getMessageId() {
            if (canonicalId == null) {
                canonicalId = canonicalIdCalculator.apply(getProtobufMessage());
            }
            return canonicalId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CustomMessage that = (CustomMessage) o;
            return message.equals(that.message);
        }

        @Override
        public int hashCode() {
            return message.hashCode();
        }
    }

    static class MessageMap<V> extends AbstractMap<PubsubMessage, V> {
        Map<Object, String> fastToCanonicalId = new HashMap<>();
        Map<String, Entry<PubsubMessage, V>> canonicalIdToMsg = new HashMap<>();

        @NotNull
        @Override
        public Set<Entry<PubsubMessage, V>> entrySet() {
            return Set.copyOf(canonicalIdToMsg.values());
        }

        @Override
        public V get(Object key) {
            if (key instanceof CustomMessage) {
                return get((CustomMessage) key);
            } else {
                throw new IllegalArgumentException();
            }
        }

        public V get(CustomMessage key) {
            String canonicalId = fastToCanonicalId.get(key.fastMessageId());
            Entry<PubsubMessage, V> entry = canonicalIdToMsg.get(canonicalId != null ? canonicalId : key.getMessageId());
            return entry == null ? null : entry.getValue();
        }

        @Override
        public V put(PubsubMessage key, V value) {
            if (key instanceof CustomMessage) {
                return put((CustomMessage) key, value);
            } else {
                throw new IllegalArgumentException();
            }
        }
        public V put(CustomMessage key, V value) {
            fastToCanonicalId.put(key.fastMessageId(), new String(key.getMessageId()));
            Entry<PubsubMessage, V> oldVal =
                    canonicalIdToMsg.put(new String(key.getMessageId()), new SimpleEntry<>(key, value));
            return oldVal == null ? null : oldVal.getValue();
        }

        @Override
        public V remove(Object key) {
            if (key instanceof CustomMessage) {
                return remove((CustomMessage) key);
            } else {
                throw new IllegalArgumentException();
            }
        }

        public V remove(CustomMessage key) {
            String canonicalId = fastToCanonicalId.remove(key.fastMessageId());
            Entry<PubsubMessage, V> entry =
                    canonicalIdToMsg.remove(canonicalId != null ? canonicalId : key.getMessageId());
            return entry == null ? null : entry.getValue();
        }

        public boolean contains(CustomMessage msg) {
            if (fastToCanonicalId.containsKey(msg.fastMessageId())) {
                return true;
            } else {
                return canonicalIdToMsg.containsKey(msg.getMessageId());
            }
        }
    }
}
