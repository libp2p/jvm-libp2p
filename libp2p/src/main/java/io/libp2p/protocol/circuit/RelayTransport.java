package io.libp2p.protocol.circuit;

import io.libp2p.core.*;
import io.libp2p.core.Stream;
import io.libp2p.core.multiformats.*;
import io.libp2p.core.mux.*;
import io.libp2p.core.security.*;
import io.libp2p.core.transport.*;
import io.libp2p.etc.*;
import io.libp2p.transport.*;
import io.netty.channel.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.stream.*;
import kotlin.*;
import org.jetbrains.annotations.*;

public class RelayTransport implements Transport, HostConsumer {
  private Host us;
  private final Map<PeerId, RelayState> listeners = new ConcurrentHashMap<>();
  private final Map<Multiaddr, Stream> dials = new ConcurrentHashMap<>();
  private final Function<Host, List<CandidateRelay>> candidateRelays;
  private final CircuitHopProtocol.Binding hop;
  private final CircuitStopProtocol.Binding stop;
  public final ConnectionUpgrader upgrader;
  private final AtomicInteger relayCount;
  private final ScheduledExecutorService runner;

  public RelayTransport(
      CircuitHopProtocol.Binding hop,
      CircuitStopProtocol.Binding stop,
      ConnectionUpgrader upgrader,
      Function<Host, List<CandidateRelay>> candidateRelays,
      ScheduledExecutorService runner) {
    this.hop = hop;
    this.stop = stop;
    this.upgrader = upgrader;
    this.candidateRelays = candidateRelays;
    this.relayCount = new AtomicInteger(0);
    this.runner = runner;
  }

  @Override
  public void setHost(Host us) {
    this.us = us;
    hop.setHost(us);
  }

  public static class CandidateRelay {
    public final PeerId id;
    public final List<Multiaddr> addrs;

    public CandidateRelay(PeerId id, List<Multiaddr> addrs) {
      this.id = id;
      this.addrs = addrs;
    }
  }

  private static class RelayState {
    List<Multiaddr> addrs;
    CircuitHopProtocol.HopController controller;
    Connection conn;
    LocalDateTime renewAfter;
  }

  public void setRelayCount(int count) {
    relayCount.set(count);
  }

  @Override
  public int getActiveConnections() {
    return dials.size();
  }

  @Override
  public int getActiveListeners() {
    return listeners.size();
  }

  @NotNull
  @Override
  public CompletableFuture<Unit> close() {
    return CompletableFuture.allOf(
            dials.values().stream().map(Stream::close).toArray(CompletableFuture[]::new))
        .thenApply(
            x -> {
              dials.clear();
              return null;
            });
  }

  static class ConnectionOverStream implements Connection {
    private final boolean isInitiator;
    private final Transport transport;
    private final Stream stream;
    private SecureChannel.Session security;
    private StreamMuxer.Session muxer;

    public ConnectionOverStream(boolean isInitiator, Transport transport, Stream stream) {
      this.isInitiator = isInitiator;
      this.transport = transport;
      this.stream = stream;
    }

    @NotNull
    @Override
    public Multiaddr localAddress() {
      return stream.getConnection().localAddress().withComponent(Protocol.P2PCIRCUIT);
    }

    @NotNull
    @Override
    public Multiaddr remoteAddress() {
      return stream.getConnection().remoteAddress().withComponent(Protocol.P2PCIRCUIT);
    }

    public void setSecureSession(SecureChannel.Session sec) {
      this.security = sec;
    }

    @NotNull
    @Override
    public SecureChannel.Session secureSession() {
      return security;
    }

    public void setMuxerSession(StreamMuxer.Session mux) {
      this.muxer = mux;
    }

    @NotNull
    @Override
    public StreamMuxer.Session muxerSession() {
      return muxer;
    }

    @NotNull
    @Override
    public Transport transport() {
      return transport;
    }

    @Override
    public boolean isInitiator() {
      return isInitiator;
    }

    @Override
    public void addHandlerBefore(
        @NotNull String s, @NotNull String s1, @NotNull ChannelHandler channelHandler) {
      stream.addHandlerBefore(s, s1, channelHandler);
    }

    @NotNull
    @Override
    public CompletableFuture<Unit> close() {
      return stream.close();
    }

    @NotNull
    @Override
    public CompletableFuture<Unit> closeFuture() {
      return stream.closeFuture();
    }

    @Override
    public void pushHandler(@NotNull ChannelHandler channelHandler) {
      stream.pushHandler(channelHandler);
    }

    @Override
    public void pushHandler(@NotNull String s, @NotNull ChannelHandler channelHandler) {
      stream.pushHandler(s, channelHandler);
    }
  }

  @NotNull
  @Override
  public CompletableFuture<Connection> dial(
      @NotNull Multiaddr multiaddr,
      @NotNull ConnectionHandler connHandler,
      @Nullable ChannelVisitor<P2PChannel> channelVisitor) {
    // first connect to relay over hop
    List<MultiaddrComponent> comps = multiaddr.getComponents();
    int split = comps.indexOf(new MultiaddrComponent(Protocol.P2PCIRCUIT, null));
    Multiaddr relay = new Multiaddr(comps.subList(0, split));
    Multiaddr target = new Multiaddr(comps.subList(split, comps.size()));
    CircuitHopProtocol.HopController ctr = hop.dial(us, relay).getController().join();
    // request proxy to target
    Stream stream = ctr.connect(target.getPeerId()).join();
    // upgrade with sec and muxer
    return upgradeStream(stream, true, upgrader, this, target.getPeerId(), connHandler);
  }

  public static CompletableFuture<Connection> upgradeStream(
      Stream stream,
      boolean isInitiator,
      ConnectionUpgrader upgrader,
      Transport transport,
      PeerId remote,
      ConnectionHandler connHandler) {
    ConnectionOverStream conn = new ConnectionOverStream(isInitiator, transport, stream);
    CompletableFuture<Connection> res = new CompletableFuture<>();
    stream.pushHandler(
        new ChannelInitializer<>() {
          @Override
          protected void initChannel(Channel channel) throws Exception {
            channel.attr(AttributesKt.getREMOTE_PEER_ID()).set(remote);
            channel.attr(AttributesKt.getCONNECTION()).set(conn);
            upgrader
                .establishSecureChannel(conn)
                .thenCompose(
                    sess -> {
                      conn.setSecureSession(sess);
                      if (sess.getEarlyMuxer() != null) {
                        return ConnectionUpgrader.Companion.establishMuxer(
                            sess.getEarlyMuxer(), conn);
                      } else {
                        return upgrader.establishMuxer(conn);
                      }
                    })
                .thenAccept(
                    sess -> {
                      conn.setMuxerSession(sess);
                      connHandler.handleConnection(conn);
                      res.complete(conn);
                    })
                .exceptionally(
                    t -> {
                      res.completeExceptionally(t);
                      return null;
                    });
            channel.pipeline().fireChannelActive();
          }
        });
    return res;
  }

  @Override
  public boolean handles(@NotNull Multiaddr multiaddr) {
    return multiaddr.hasAny(Protocol.P2PCIRCUIT);
  }

  @Override
  public void initialize() {
    stop.setTransport(this);
    // find relays and connect and reserve
    runner.scheduleAtFixedRate(this::ensureEnoughCurrentRelays, 0, 2 * 60, TimeUnit.SECONDS);
  }

  public void ensureEnoughCurrentRelays() {
    int active = 0;
    // renew existing relays before finding new ones
    Set<Map.Entry<PeerId, RelayState>> currentRelays = listeners.entrySet();
    for (Map.Entry<PeerId, RelayState> current : currentRelays) {
      RelayState relay = current.getValue();
      LocalDateTime now = LocalDateTime.now();
      if (now.isBefore(relay.renewAfter)) {
        active++;
      } else {
        try {
          CircuitHopProtocol.Reservation reservation = relay.controller.reserve().join();
          relay.renewAfter = reservation.expiry.minusMinutes(1);
          active++;
        } catch (Exception e) {
          listeners.remove(current.getKey());
        }
      }
    }
    if (active >= relayCount.get()) return;

    List<CandidateRelay> candidates = candidateRelays.apply(us);
    for (CandidateRelay candidate : candidates) {
      // connect to relay and get reservation
      CircuitHopProtocol.HopController ctr =
          hop.dial(us, candidate.id, candidate.addrs.toArray(new Multiaddr[0]))
              .getController()
              .join();
      CircuitHopProtocol.Reservation resv = ctr.reserve().join();
      active++;
      listeners.put(candidate.id, new RelayState());
      if (active >= relayCount.get()) return;
    }
  }

  @NotNull
  @Override
  public CompletableFuture<Unit> listen(
      @NotNull Multiaddr relayAddr,
      @NotNull ConnectionHandler connectionHandler,
      @Nullable ChannelVisitor<P2PChannel> channelVisitor) {
    List<MultiaddrComponent> components = relayAddr.getComponents();
    Multiaddr withoutCircuit = new Multiaddr(components.subList(0, components.size() - 1));
    CircuitHopProtocol.HopController ctr = hop.dial(us, withoutCircuit).getController().join();
    return ctr.reserve().thenApply(res -> null);
  }

  @NotNull
  @Override
  public List<Multiaddr> listenAddresses() {
    return listeners.entrySet().stream()
        .flatMap(
            r ->
                r.getValue().addrs.stream()
                    .map(
                        a ->
                            a.withP2P(r.getKey())
                                .concatenated(
                                    new Multiaddr(
                                            List.of(
                                                new MultiaddrComponent(Protocol.P2PCIRCUIT, null)))
                                        .withP2P(us.getPeerId()))))
        .collect(Collectors.toList());
  }

  @NotNull
  @Override
  public CompletableFuture<Unit> unlisten(@NotNull Multiaddr multiaddr) {
    RelayState relayState = listeners.get(multiaddr);
    if (relayState == null) return CompletableFuture.completedFuture(null);
    return relayState.conn.close();
  }
}
