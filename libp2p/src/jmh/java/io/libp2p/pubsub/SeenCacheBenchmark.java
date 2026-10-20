package io.libp2p.pubsub;

import io.libp2p.core.pubsub.ValidationResult;
import io.libp2p.etc.types.WBytes;
import io.libp2p.pubsub.LegacySeenCaches.LegacyFastIdSeenCache;
import io.libp2p.pubsub.LegacySeenCaches.LegacyTTLSeenCache;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import pubsub.pb.Rpc;

/**
 * The seen cache as the router drives it, {@code TTLSeenCache(FastIdSeenCache)} pre-filled with
 * {@code size} messages, before ({@code LEGACY}) and after the single-entry restructuring.
 *
 * <ul>
 *   <li>{@code duplicate}: an inbound copy of a seen message, i.e. what every mesh peer forwards.
 *       Legacy: {@code get} + {@code getSeenMessageCached}; new: {@code getSeen}.
 *   <li>{@code firstDelivery}: an unseen message: {@code get} (miss), {@code put(empty)} on
 *       receipt, {@code put(valid)} after validation, and the TTL eviction of the message that
 *       entered the cache {@code size} messages earlier, so the cache stays at a steady size.
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xmx6g", "-Xms6g"})
public class SeenCacheBenchmark {

  private static final int RING = 1 << 16;
  private static final int RING_MASK = RING - 1;
  private static final Optional<ValidationResult> PENDING = Optional.empty();
  private static final Optional<ValidationResult> VALID = Optional.of(ValidationResult.Valid);

  public enum Impl {
    LEGACY,
    NEW
  }

  @Param({"LEGACY", "NEW"})
  public Impl impl;

  @Param({"100000", "2000000"})
  public int size;

  private SeenCache<Optional<ValidationResult>> cache;
  private long clock;
  private PubsubMessage[] duplicates;
  private PubsubMessage[] fresh;
  private int index;

  @Setup(Level.Trial)
  public void setUp() {
    final Random random = new Random(4321);
    final SeenCache<Optional<ValidationResult>> inner;
    if (impl == Impl.LEGACY) {
      inner = new LegacyFastIdSeenCache<>(msg -> new DigestKey(msg.messageSha256()));
      cache = new LegacyTTLSeenCache<>(inner, Duration.ofMillis(size), () -> clock);
    } else {
      inner = new FastIdSeenCache<>(msg -> new DigestKey(msg.messageSha256()));
      cache = new TTLSeenCache<>(inner, Duration.ofMillis(size), () -> clock);
    }
    final byte[][] digests = new byte[size][];
    for (int i = 0; i < size; i++) {
      clock = i;
      digests[i] = randomBytes(random, 32);
      final PubsubMessage message = new DigestMessage(digests[i], randomBytes(random, 20));
      cache.put(message, PENDING);
      cache.put(message, VALID);
    }
    duplicates = new PubsubMessage[RING];
    fresh = new PubsubMessage[RING];
    for (int i = 0; i < RING; i++) {
      // A duplicate arrives as a new instance carrying its own copy of the wire bytes.
      duplicates[i] =
          new DigestMessage(digests[random.nextInt(size)].clone(), randomBytes(random, 20));
      fresh[i] = new DigestMessage(randomBytes(random, 32), randomBytes(random, 20));
    }
    System.gc();
    final Runtime rt = Runtime.getRuntime();
    System.out.printf(
        "%n# %s size=%d: cache=%d entries, heap used after fill %d MB%n",
        impl, size, cache.getSize(), (rt.totalMemory() - rt.freeMemory()) >> 20);
  }

  @Benchmark
  public void duplicate(final Blackhole blackhole) {
    final PubsubMessage msg = duplicates[next()];
    if (impl == Impl.LEGACY) {
      final Optional<ValidationResult> result = cache.get(msg);
      if (result != null) {
        blackhole.consume(cache.getSeenMessageCached(msg));
        blackhole.consume(result);
      }
    } else {
      final SeenEntry<Optional<ValidationResult>> seen = cache.getSeen(msg);
      if (seen != null) {
        blackhole.consume(seen.getMessage());
        blackhole.consume(seen.getValue());
      }
    }
  }

  @Benchmark
  public void firstDelivery(final Blackhole blackhole) {
    final int i = next();
    // a fresh instance each time, as the router's message factory produces one per inbound copy
    final DigestMessage msg = ((DigestMessage) fresh[i]).copy();
    blackhole.consume(impl == Impl.LEGACY ? cache.get(msg) : cache.getSeen(msg));
    clock++;
    cache.put(msg, PENDING);
    cache.put(msg, VALID);
    // keep the ring reusable: the message stays in the cache until the TTL evicts it, so the next
    // use of this ring slot (RING puts later) must look like a new message again
    fresh[i] = new DigestMessage(successor(msg.digest), successor(msg.messageIdBytes));
  }

  private int next() {
    index = (index + 1) & RING_MASK;
    return index;
  }

  private static byte[] randomBytes(final Random random, final int length) {
    final byte[] bytes = new byte[length];
    random.nextBytes(bytes);
    return bytes;
  }

  /**
   * Cheap successor: the bytes as a little-endian counter plus one, distinct from every earlier
   * value.
   */
  private static byte[] successor(final byte[] previous) {
    final byte[] next = previous.clone();
    for (int i = 0; i < next.length; i++) {
      if (++next[i] != 0) {
        break;
      }
    }
    return next;
  }

  /** A seen-cache key like Teku's FastMessageId: cached hash from the digest's first bytes. */
  static final class DigestKey {
    private final byte[] digest;
    private final int hash;

    DigestKey(final byte[] digest) {
      this.digest = digest;
      this.hash =
          ((digest[0] & 0xff) << 24)
              | ((digest[1] & 0xff) << 16)
              | ((digest[2] & 0xff) << 8)
              | (digest[3] & 0xff);
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof DigestKey && Arrays.equals(digest, ((DigestKey) other).digest);
    }
  }

  /** Carries only what the seen cache touches: the wire-bytes digest and the message id. */
  static final class DigestMessage implements PubsubMessage {
    private static final Rpc.Message EMPTY = Rpc.Message.getDefaultInstance();
    final byte[] digest;
    final byte[] messageIdBytes;
    private final WBytes messageId;

    DigestMessage(final byte[] digest, final byte[] messageIdBytes) {
      this.digest = digest;
      this.messageIdBytes = messageIdBytes;
      this.messageId = new WBytes(messageIdBytes);
    }

    DigestMessage copy() {
      return new DigestMessage(digest.clone(), messageIdBytes);
    }

    @NotNull
    @Override
    public byte[] messageSha256() {
      return digest;
    }

    @NotNull
    @Override
    public WBytes getMessageId() {
      return messageId;
    }

    @NotNull
    @Override
    public Rpc.Message getProtobufMessage() {
      return EMPTY;
    }

    @NotNull
    @Override
    public List<String> getTopics() {
      return List.of();
    }

    @Override
    public boolean equals(final Object other) {
      return this == other;
    }

    @Override
    public int hashCode() {
      return messageId.hashCode();
    }
  }
}
