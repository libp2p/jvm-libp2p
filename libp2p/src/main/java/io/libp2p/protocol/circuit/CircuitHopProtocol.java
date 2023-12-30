package io.libp2p.protocol.circuit;

import com.google.protobuf.*;
import io.libp2p.core.*;
import io.libp2p.core.Stream;
import io.libp2p.core.crypto.*;
import io.libp2p.core.multiformats.*;
import io.libp2p.core.multistream.*;
import io.libp2p.etc.util.netty.*;
import io.libp2p.protocol.*;
import io.libp2p.protocol.circuit.crypto.pb.*;
import io.libp2p.protocol.circuit.pb.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import java.io.*;
import java.nio.charset.*;
import java.time.*;
import java.time.Duration;
import java.time.temporal.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import io.netty.handler.codec.protobuf.*;
import org.jetbrains.annotations.*;

public class CircuitHopProtocol extends ProtobufProtocolHandler<CircuitHopProtocol.HopController> {

  private static final String HOP_HANDLER_NAME = "HOP_HANDLER";
  private static final String STREAM_CLEARER_NAME = "STREAM_CLEARER";

  public static class Binding extends StrictProtocolBinding<HopController> implements HostConsumer {
    private final CircuitHopProtocol hop;

    private Binding(CircuitHopProtocol hop) {
      super("/libp2p/circuit/relay/0.2.0/hop", hop);
      this.hop = hop;
    }

    public Binding(RelayManager manager, CircuitStopProtocol.Binding stop) {
      this(new CircuitHopProtocol(manager, stop));
    }

    @Override
    public void setHost(Host us) {
      hop.setHost(us);
    }
  }

  private static void putUvarint(OutputStream out, long x) throws IOException {
    while (x >= 0x80) {
      out.write((byte) (x | 0x80));
      x >>= 7;
    }
    out.write((byte) x);
  }

  public static byte[] createVoucher(
      PrivKey priv, PeerId relay, PeerId requestor, LocalDateTime expiry) {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try {
      putUvarint(bout, 0x0302);
    } catch (IOException e) {
    }
    byte[] typeMulticodec = bout.toByteArray();
    byte[] payload =
        VoucherOuterClass.Voucher.newBuilder()
            .setRelay(ByteString.copyFrom(relay.getBytes()))
            .setPeer(ByteString.copyFrom(requestor.getBytes()))
            .setExpiration(expiry.toEpochSecond(ZoneOffset.UTC) * 1_000_000_000)
            .build()
            .toByteArray();
    byte[] signDomain = "libp2p-relay-rsvp".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream toSign = new ByteArrayOutputStream();
    try {
      putUvarint(toSign, signDomain.length);
      toSign.write(signDomain);
      putUvarint(toSign, typeMulticodec.length);
      toSign.write(typeMulticodec);
      putUvarint(toSign, payload.length);
      toSign.write(payload);
    } catch (IOException e) {
    }
    byte[] signature = priv.sign(toSign.toByteArray());
    return Crypto.Envelope.newBuilder()
        .setPayloadType(ByteString.copyFrom(typeMulticodec))
        .setPayload(ByteString.copyFrom(payload))
        .setPublicKey(
            Crypto.PublicKey.newBuilder()
                .setTypeValue(priv.publicKey().getKeyType().getNumber())
                .setData(ByteString.copyFrom(priv.publicKey().raw())))
        .setSignature(ByteString.copyFrom(signature))
        .build()
        .toByteArray();
  }

  public static class Reservation {
    public final LocalDateTime expiry;
    public final int durationSeconds;
    public final long maxBytes;
    public final byte[] voucher;
    public final Multiaddr[] addrs;

    public Reservation(LocalDateTime expiry, int durationSeconds, long maxBytes, byte[] voucher, Multiaddr[] addrs) {
      this.expiry = expiry;
      this.durationSeconds = durationSeconds;
      this.maxBytes = maxBytes;
      this.voucher = voucher;
      this.addrs = addrs;
    }
  }

  public interface RelayManager {
    boolean hasReservation(PeerId source);

    Optional<Reservation> createReservation(PeerId requestor, Multiaddr addr);

    Optional<Reservation> allowConnection(PeerId target, PeerId initiator);

    static RelayManager limitTo(PrivKey priv, PeerId relayPeerId, int concurrent) {
      return new RelayManager() {
        Map<PeerId, Reservation> reservations = new HashMap<>();

        @Override
        public synchronized boolean hasReservation(PeerId source) {
          return reservations.containsKey(source);
        }

        @Override
        public synchronized Optional<Reservation> createReservation(PeerId requestor, Multiaddr addr) {
          if (reservations.size() >= concurrent) return Optional.empty();
          LocalDateTime now = LocalDateTime.now();
          LocalDateTime expiry = now.plusHours(1);
          byte[] voucher = createVoucher(priv, relayPeerId, requestor, now);
          Reservation resv = new Reservation(expiry, 120, 4096, voucher, new Multiaddr[]{addr});
          reservations.put(requestor, resv);
          return Optional.of(resv);
        }

        @Override
        public synchronized Optional<Reservation> allowConnection(PeerId target, PeerId initiator) {
          return Optional.ofNullable(reservations.get(target));
        }
      };
    }
  }

  public interface HopController {
    CompletableFuture<Circuit.HopMessage> rpc(Circuit.HopMessage req);

    default CompletableFuture<Reservation> reserve() {
      return rpc(Circuit.HopMessage.newBuilder().setType(Circuit.HopMessage.Type.RESERVE).build())
          .thenApply(
              msg -> {
                if (msg.getStatus() == Circuit.Status.OK) {
                  long expiry = msg.getReservation().getExpire();
                  return new Reservation(
                          LocalDateTime.ofEpochSecond(expiry, 0, ZoneOffset.UTC),
                          msg.getLimit().getDuration(),
                          msg.getLimit().getData(),
                          msg.getReservation().getVoucher().toByteArray(),
                          null);
                }
                throw new IllegalStateException(msg.getStatus().name());
              });
    }

    CompletableFuture<Stream> connect(PeerId target);
  }

  public static class HopRemover extends ChannelInitializer {

    @Override
    protected void initChannel(@NotNull Channel ch) throws Exception {
      System.out.println("Removed Hop handler");
      ch.pipeline().remove(HOP_HANDLER_NAME);
      // also remove associated protobuf handlers
      ch.pipeline().remove(ProtobufDecoder.class);
      ch.pipeline().remove(ProtobufEncoder.class);
      ch.pipeline().remove(ProtobufVarint32FrameDecoder.class);
      ch.pipeline().remove(ProtobufVarint32LengthFieldPrepender.class);
      ch.pipeline().remove(STREAM_CLEARER_NAME);
    }
  }

  public static class Sender implements ProtocolMessageHandler<Circuit.HopMessage>, HopController {
    private final Stream stream;
    private final LinkedBlockingDeque<CompletableFuture<Circuit.HopMessage>> queue =
        new LinkedBlockingDeque<>();

    public Sender(Stream stream) {
      this.stream = stream;
    }

    @Override
    public void onMessage(@NotNull Stream stream, Circuit.HopMessage msg) {
      queue.poll().complete(msg);
    }

    public CompletableFuture<Circuit.HopMessage> rpc(Circuit.HopMessage req) {
      CompletableFuture<Circuit.HopMessage> res = new CompletableFuture<>();
      queue.add(res);
      stream.writeAndFlush(req);
      return res;
    }

    @Override
    public CompletableFuture<Stream> connect(PeerId target) {
      return rpc(Circuit.HopMessage.newBuilder()
              .setType(Circuit.HopMessage.Type.CONNECT)
              .setPeer(Circuit.Peer.newBuilder().setId(ByteString.copyFrom(target.getBytes())))
              .build())
          .thenApply(
              msg -> {
                if (msg.getType() == Circuit.HopMessage.Type.STATUS
                    && msg.getStatus() == Circuit.Status.OK){
                  // remove handler for HOP to return bare stream
                  stream.pushHandler(STREAM_CLEARER_NAME, new HopRemover());
                  return stream;
                }
                throw new IllegalStateException("Circuit dial returned " + msg.getStatus().name());
              });
    }
  }

  public static class Receiver
      implements ProtocolMessageHandler<Circuit.HopMessage>, HopController {
    private final Host us;
    private final RelayManager manager;
    private final Supplier<List<Multiaddr>> publicAddresses;
    private final CircuitStopProtocol.Binding stop;
    private final AddressBook addressBook;

    public Receiver(
        Host us,
        RelayManager manager,
        Supplier<List<Multiaddr>> publicAddresses,
        CircuitStopProtocol.Binding stop,
        AddressBook addressBook) {
      this.us = us;
      this.manager = manager;
      this.publicAddresses = publicAddresses;
      this.stop = stop;
      this.addressBook = addressBook;
    }

    @Override
    public void onMessage(@NotNull Stream stream, Circuit.HopMessage msg) {
      switch (msg.getType()) {
        case RESERVE:
          {
            PeerId requestor = stream.remotePeerId();
            Optional<Reservation> reservation = manager.createReservation(requestor, stream.getConnection().remoteAddress());
            if (reservation.isEmpty()
                || new Multiaddr(stream.getConnection().remoteAddress().toString())
                    .has(Protocol.P2PCIRCUIT)) {
              stream.writeAndFlush(
                  Circuit.HopMessage.newBuilder()
                      .setType(Circuit.HopMessage.Type.STATUS)
                      .setStatus(Circuit.Status.RESERVATION_REFUSED));
              return;
            }
            Reservation resv = reservation.get();
            stream.writeAndFlush(
                Circuit.HopMessage.newBuilder()
                    .setType(Circuit.HopMessage.Type.STATUS)
                    .setStatus(Circuit.Status.OK)
                    .setReservation(
                        Circuit.Reservation.newBuilder()
                            .setExpire(resv.expiry.toEpochSecond(ZoneOffset.UTC))
                            .addAllAddrs(
                                publicAddresses.get().stream()
                                    .map(a -> ByteString.copyFrom(a.serialize()))
                                    .collect(Collectors.toList()))
                            .setVoucher(ByteString.copyFrom(resv.voucher)))
                    .setLimit(
                        Circuit.Limit.newBuilder()
                            .setDuration(resv.durationSeconds)
                            .setData(resv.maxBytes)));
          }
        case CONNECT:
          {
            PeerId target = new PeerId(msg.getPeer().getId().toByteArray());
            if (manager.hasReservation(target)) {
              PeerId initiator = stream.remotePeerId();
              Optional<Reservation> res = manager.allowConnection(target, initiator);
              if (res.isPresent()) {
                Reservation resv = res.get();
                try {
                  CircuitStopProtocol.StopController stop =
                      this.stop
                          .dial(
                              us,
                              target,
                              resv.addrs)
                          .getController()
                          .orTimeout(15, TimeUnit.SECONDS)
                          .join();
                  Circuit.StopMessage reply =
                      stop.connect(initiator, resv.durationSeconds, resv.maxBytes).join();
                  if (reply.getStatus().equals(Circuit.Status.OK)) {
                    stream.writeAndFlush(
                        Circuit.HopMessage.newBuilder()
                            .setType(Circuit.HopMessage.Type.STATUS)
                            .setStatus(Circuit.Status.OK));
                    Stream toTarget = stop.getStream();
                    Stream fromRequestor = stream;
                    // remove hop and stop handlers from streams before proxying
                    fromRequestor.pushHandler(STREAM_CLEARER_NAME, new HopRemover());
                    toTarget.pushHandler(CircuitStopProtocol.STOP_REMOVER_NAME, new CircuitStopProtocol.StopRemover());

                    // connect these streams with time + bytes enforcement
                    fromRequestor.pushHandler(new InboundTrafficLimitHandler(resv.maxBytes));
                    fromRequestor.pushHandler(
                        new TotalTimeoutHandler(
                            Duration.of(resv.durationSeconds, ChronoUnit.SECONDS)));
                    toTarget.pushHandler(new InboundTrafficLimitHandler(resv.maxBytes));
                    toTarget.pushHandler(
                        new TotalTimeoutHandler(
                            Duration.of(resv.durationSeconds, ChronoUnit.SECONDS)));
                    fromRequestor.pushHandler(new ProxyHandler(toTarget));
                    toTarget.pushHandler(new ProxyHandler(fromRequestor));
                  } else {
                    stream.writeAndFlush(
                        Circuit.HopMessage.newBuilder()
                            .setType(Circuit.HopMessage.Type.STATUS)
                            .setStatus(reply.getStatus()));
                  }
                } catch (Exception e) {
                  stream.writeAndFlush(
                      Circuit.HopMessage.newBuilder()
                          .setType(Circuit.HopMessage.Type.STATUS)
                          .setStatus(Circuit.Status.CONNECTION_FAILED));
                }
              } else {
                stream.writeAndFlush(
                    Circuit.HopMessage.newBuilder()
                        .setType(Circuit.HopMessage.Type.STATUS)
                        .setStatus(Circuit.Status.RESOURCE_LIMIT_EXCEEDED));
              }
            } else {
              stream.writeAndFlush(
                  Circuit.HopMessage.newBuilder()
                      .setType(Circuit.HopMessage.Type.STATUS)
                      .setStatus(Circuit.Status.NO_RESERVATION));
            }
          }
      }
    }

    @Override
    public CompletableFuture<Stream> connect(PeerId target) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Cannot send from a receiver!"));
    }

    public CompletableFuture<Circuit.HopMessage> rpc(Circuit.HopMessage msg) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Cannot send from a receiver!"));
    }
  }

  private static class ProxyHandler extends ChannelInboundHandlerAdapter {

    private final Stream target;

    public ProxyHandler(Stream target) {
      this.target = target;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof ByteBuf) {
        target.writeAndFlush(msg);
        ((ByteBuf) msg).release();
      }
    }
  }

  private static final int TRAFFIC_LIMIT = 2 * 1024;
  private final RelayManager manager;
  private final CircuitStopProtocol.Binding stop;
  private Host us;

  public CircuitHopProtocol(RelayManager manager, CircuitStopProtocol.Binding stop) {
    super(Circuit.HopMessage.getDefaultInstance(), TRAFFIC_LIMIT, TRAFFIC_LIMIT);
    this.manager = manager;
    this.stop = stop;
  }

  public void setHost(Host us) {
    this.us = us;
  }

  @NotNull
  @Override
  protected CompletableFuture<HopController> onStartInitiator(@NotNull Stream stream) {
    Sender replyPropagator = new Sender(stream);
    stream.pushHandler(HOP_HANDLER_NAME, new ProtocolMessageHandlerAdapter<>(stream, replyPropagator));
    return CompletableFuture.completedFuture(replyPropagator);
  }

  @NotNull
  @Override
  protected CompletableFuture<HopController> onStartResponder(@NotNull Stream stream) {
    if (us == null) throw new IllegalStateException("null Host for us!");
    Supplier<List<Multiaddr>> ourpublicAddresses = () -> us.listenAddresses();
    Receiver dialer = new Receiver(us, manager, ourpublicAddresses, stop, us.getAddressBook());
    stream.pushHandler(HOP_HANDLER_NAME, new ProtocolMessageHandlerAdapter<>(stream, dialer));
    return CompletableFuture.completedFuture(dialer);
  }
}
