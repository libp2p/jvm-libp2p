package io.libp2p.pubsub;

import io.libp2p.etc.types.MutableBiMultiMap;
import io.libp2p.etc.types.MutableBiMultiMapKt;
import io.libp2p.etc.types.WBytes;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.NotNull;
import pubsub.pb.Rpc;

/**
 * Verbatim ports of {@code FastIdSeenCache} and {@code TTLSeenCache} as they were before the
 * single-entry restructuring, kept here only as the baseline for {@link SeenCacheBenchmark}.
 */
final class LegacySeenCaches {
  private LegacySeenCaches() {}

  static final class LegacyFastIdSeenCache<TValue> implements SeenCache<TValue> {
    private final Function<PubsubMessage, Object> fastIdFunction;
    final MutableBiMultiMap<Object, WBytes> fastIdMap = MutableBiMultiMapKt.mutableBiMultiMap();
    final Map<WBytes, TValue> slowIdMap = new HashMap<>();

    LegacyFastIdSeenCache(Function<PubsubMessage, Object> fastIdFunction) {
      this.fastIdFunction = fastIdFunction;
    }

    @Override
    public int getSize() {
      return slowIdMap.size();
    }

    @NotNull
    @Override
    public PubsubMessage getSeenMessageCached(@NotNull PubsubMessage msg) {
      WBytes slowId = fastIdMap.getValue(fastIdFunction.apply(msg));
      if (slowId == null) {
        return msg;
      }
      if (msg instanceof LegacyFastIdPubsubMessage) {
        return msg;
      }
      return new LegacyFastIdPubsubMessage(msg, slowId);
    }

    @Override
    public TValue get(@NotNull PubsubMessage msg) {
      WBytes slowId = fastIdMap.getValue(fastIdFunction.apply(msg));
      if (slowId == null) {
        slowId = msg.getMessageId();
      }
      return slowIdMap.get(slowId);
    }

    @Override
    public boolean isSeen(@NotNull PubsubMessage msg) {
      return fastIdMap.getValue(fastIdFunction.apply(msg)) != null
          || slowIdMap.containsKey(msg.getMessageId());
    }

    @Override
    public boolean isSeen(@NotNull WBytes messageId) {
      return slowIdMap.containsKey(messageId);
    }

    @Override
    public void put(@NotNull PubsubMessage msg, TValue value) {
      fastIdMap.put(fastIdFunction.apply(msg), msg.getMessageId());
      slowIdMap.put(msg.getMessageId(), value);
    }

    @Override
    public void remove(@NotNull WBytes messageId) {
      slowIdMap.remove(messageId);
      fastIdMap.removeAllByValue(messageId);
    }
  }

  /** The pre-restructuring wrapper: only the message id is cached. */
  static final class LegacyFastIdPubsubMessage implements PubsubMessage {
    private final PubsubMessage delegate;
    private final WBytes messageId;

    LegacyFastIdPubsubMessage(PubsubMessage delegate, WBytes messageId) {
      this.delegate = delegate;
      this.messageId = messageId;
    }

    @NotNull
    @Override
    public Rpc.Message getProtobufMessage() {
      return delegate.getProtobufMessage();
    }

    @NotNull
    @Override
    public WBytes getMessageId() {
      return messageId;
    }

    @NotNull
    @Override
    public List<String> getTopics() {
      return delegate.getTopics();
    }

    @NotNull
    @Override
    public byte[] messageSha256() {
      return delegate.messageSha256();
    }

    @Override
    public boolean equals(Object other) {
      return delegate.equals(other);
    }

    @Override
    public int hashCode() {
      return delegate.hashCode();
    }
  }

  static final class LegacyTTLSeenCache<TValue> implements SeenCache<TValue> {
    private static final class TimedMessage {
      final long time;
      final WBytes messageId;

      TimedMessage(long time, WBytes messageId) {
        this.time = time;
        this.messageId = messageId;
      }
    }

    private final SeenCache<TValue> delegate;
    private final long ttlMillis;
    private final LongSupplier curTime;
    final LinkedList<TimedMessage> putTimes = new LinkedList<>();

    LegacyTTLSeenCache(SeenCache<TValue> delegate, Duration ttl, LongSupplier curTime) {
      this.delegate = delegate;
      this.ttlMillis = ttl.toMillis();
      this.curTime = curTime;
    }

    @Override
    public int getSize() {
      return delegate.getSize();
    }

    @Override
    public void put(@NotNull PubsubMessage msg, TValue value) {
      delegate.put(msg, value);
      putTimes.add(new TimedMessage(curTime.getAsLong(), msg.getMessageId()));
      pruneOld();
    }

    private void pruneOld() {
      long pruneBefore = curTime.getAsLong() - ttlMillis;
      Iterator<TimedMessage> it = putTimes.iterator();
      while (it.hasNext()) {
        TimedMessage n = it.next();
        if (n.time >= pruneBefore) {
          break;
        }
        delegate.remove(n.messageId);
        it.remove();
      }
    }

    @Override
    public TValue get(@NotNull PubsubMessage msg) {
      return delegate.get(msg);
    }

    @Override
    public boolean isSeen(@NotNull PubsubMessage msg) {
      return delegate.isSeen(msg);
    }

    @Override
    public boolean isSeen(@NotNull WBytes messageId) {
      return delegate.isSeen(messageId);
    }

    @Override
    public void remove(@NotNull WBytes messageId) {
      delegate.remove(messageId);
    }

    @NotNull
    @Override
    public PubsubMessage getSeenMessageCached(@NotNull PubsubMessage msg) {
      return delegate.getSeenMessageCached(msg);
    }
  }
}
