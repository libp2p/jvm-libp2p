package io.libp2p.pubsub;

import io.libp2p.pubsub.gossip.GossipParams;
import io.libp2p.pubsub.gossip.GossipParamsKt;
import io.libp2p.pubsub.gossip.GossipRouter;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pubsub.pb.Rpc;

import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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
        Assertions.assertEquals(10, router.getParams().getD());
        Assertions.assertEquals(20, router.getParams().getDHigh());
        Assertions.assertEquals(GossipParamsKt.defaultDScore(10), router.getParams().getDScore());
    }

    class CustomMessage implements PubsubMessage {
        final Rpc.Message message;
        Function<Rpc.Message, Object> fastIdCalculator;
        Function<Rpc.Message, String> canonicalIdCalculator;
        String canonicalId = null;

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
        public String getMessageId() {
            if (canonicalId == null) {
                canonicalId = canonicalIdCalculator.apply(getProtobufMessage());
            }
            return canonicalId;
        }

        @Override
        public boolean equals(Object o) {
            throw new UnsupportedOperationException();
//            if (this == o) return true;
//            if (o == null || getClass() != o.getClass()) return false;
//            CustomMessage that = (CustomMessage) o;
        }

        @Override
        public int hashCode() {
            throw new UnsupportedOperationException();
//            return Objects.hash(message, fastIdCalculator, canonicalIdCalculator, canonicalId);
        }
    }

    class MessageMap<V> extends AbstractMap<CustomMessage, V> {
        Map<Object, String> fastToCanonicalId;
        Map<String, Entry<CustomMessage, V>> canonicalIdToMsg;

        @NotNull
        @Override
        public Set<Entry<CustomMessage, V>> entrySet() {
            return new HashSet<>(canonicalIdToMsg.values());
        }

        @Override
        public V put(CustomMessage key, V value) {
            fastToCanonicalId.put(key.fastMessageId(), key.getMessageId());
            Entry<CustomMessage, V> oldVal = canonicalIdToMsg.put(key.getMessageId(), new SimpleEntry<>(key, value));
            return oldVal == null ? null : oldVal.getValue();
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
