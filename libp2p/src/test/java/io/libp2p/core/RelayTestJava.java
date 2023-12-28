package io.libp2p.core;

import io.libp2p.core.crypto.*;
import io.libp2p.core.dsl.*;
import io.libp2p.core.multiformats.*;
import io.libp2p.core.mux.*;
import io.libp2p.protocol.*;
import io.libp2p.protocol.circuit.*;
import io.libp2p.security.noise.*;
import io.libp2p.transport.tcp.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

public class RelayTestJava {

  private static void enableRelay(BuilderJ b, List<RelayTransport.CandidateRelay> relays) {
    PrivKey priv = b.getIdentity().random().getFactory().invoke();
    b.getIdentity().setFactory(() -> priv);
    PeerId us = PeerId.fromPubKey(priv.publicKey());
    CircuitHopProtocol.RelayManager relayManager =
        CircuitHopProtocol.RelayManager.limitTo(priv, us, 5);
    CircuitStopProtocol.Binding stop = new CircuitStopProtocol.Binding(new CircuitStopProtocol());
    CircuitHopProtocol.Binding hop = new CircuitHopProtocol.Binding(relayManager, stop);
    b.getProtocols().add(hop);
    b.getProtocols().add(stop);
    b.getTransports().add(u -> new RelayTransport(hop, stop, u, h -> relays));
  }

  @Test
  void ping() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/tcp/40002";

    Multiaddr relayAddr =
        new Multiaddr(
            "/ip4/136.244.97.135/tcp/4001/p2p/12D3KooWFQSZe27Fk3KwEBn23vEnLEknKnhPWDF7n2nZ3sutaYJ9");
    RelayTransport.CandidateRelay relay =
        new RelayTransport.CandidateRelay(relayAddr.getPeerId(), List.of(relayAddr));
    List<RelayTransport.CandidateRelay> relays = List.of(relay);

    Host clientHost =
        new HostBuilder()
            .builderModifier(b -> enableRelay(b, relays))
            .transport(TcpTransport::new)
            .secureChannel(NoiseXXSecureChannel::new)
            .muxer(StreamMuxerProtocol::getYamux)
            .protocol(new Ping())
            .build();
    clientHost.getNetwork().getTransports().stream()
        .filter(t -> t instanceof RelayTransport)
        .map(t -> (RelayTransport) t)
        .findFirst()
        .get()
        .setHost(clientHost);

    Host serverHost =
        new HostBuilder()
            .builderModifier(b -> enableRelay(b, relays))
            .transport(TcpTransport::new)
            .secureChannel(NoiseXXSecureChannel::new)
            .muxer(StreamMuxerProtocol::getYamux)
            .protocol(new Ping())
            .listen(localListenAddress)
            .listen(relayAddr + "/p2p-circuit")
            .build();
    serverHost.getNetwork().getTransports().stream()
        .filter(t -> t instanceof RelayTransport)
        .map(t -> (RelayTransport) t)
        .findFirst()
        .get()
        .setHost(serverHost);

    CompletableFuture<Void> clientStarted = clientHost.start();
    CompletableFuture<Void> serverStarted = serverHost.start();
    clientStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Client started");
    serverStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Server started");

    Multiaddr toDial =
        relayAddr.concatenated(
            new Multiaddr("/p2p-circuit/p2p/" + serverHost.getPeerId().toBase58()));
    System.out.println("Dialling " + toDial + " from " + clientHost.getPeerId());
    StreamPromise<PingController> ping =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), toDial)
            .thenApply(it -> it.muxerSession().createStream(new Ping()))
            .get(5, TimeUnit.SECONDS);

    Stream pingStream = ping.getStream().get(5, TimeUnit.SECONDS);
    System.out.println("Ping stream created");
    PingController pingCtr = ping.getController().get(5, TimeUnit.SECONDS);
    System.out.println("Ping controller created");

    for (int i = 0; i < 10; i++) {
      long latency = pingCtr.ping().get(1, TimeUnit.SECONDS);
      System.out.println("Ping is " + latency);
    }
    pingStream.close().get(5, TimeUnit.SECONDS);
    System.out.println("Ping stream closed");

    Assertions.assertThrows(
        ExecutionException.class, () -> pingCtr.ping().get(5, TimeUnit.SECONDS));

    clientHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Client stopped");
    serverHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Server stopped");
  }

  @Test
  void pingOverLocalRelay() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/tcp/40002";

    Host relayHost =
            new HostBuilder()
                    .builderModifier(b -> enableRelay(b, Collections.emptyList()))
                    .transport(TcpTransport::new)
                    .secureChannel(NoiseXXSecureChannel::new)
                    .muxer(StreamMuxerProtocol::getYamux)
                    .listen("/ip4/127.0.0.1/tcp/0")
                    .protocol(new Ping())
                    .build();
    relayHost.getNetwork().getTransports().stream()
            .filter(t -> t instanceof RelayTransport)
            .map(t -> (RelayTransport) t)
            .findFirst()
            .get()
            .setHost(relayHost);
    CompletableFuture<Void> relayStarted = relayHost.start();
    relayStarted.get(5, TimeUnit.SECONDS);

    List<Multiaddr> relayAddrs = relayHost.listenAddresses();
    Multiaddr relayAddr = relayAddrs.get(0);
    RelayTransport.CandidateRelay relay =
            new RelayTransport.CandidateRelay(relayHost.getPeerId(), relayAddrs);
    List<RelayTransport.CandidateRelay> relays = List.of(relay);

    Host clientHost =
            new HostBuilder()
                    .builderModifier(b -> enableRelay(b, relays))
                    .transport(TcpTransport::new)
                    .secureChannel(NoiseXXSecureChannel::new)
                    .muxer(StreamMuxerProtocol::getYamux)
                    .protocol(new Ping())
                    .build();
    clientHost.getNetwork().getTransports().stream()
            .filter(t -> t instanceof RelayTransport)
            .map(t -> (RelayTransport) t)
            .findFirst()
            .get()
            .setHost(clientHost);

    Host serverHost =
            new HostBuilder()
                    .builderModifier(b -> enableRelay(b, relays))
                    .transport(TcpTransport::new)
                    .secureChannel(NoiseXXSecureChannel::new)
                    .muxer(StreamMuxerProtocol::getYamux)
                    .protocol(new Ping())
                    .listen(localListenAddress)
                    .listen(relayAddr + "/p2p-circuit")
                    .build();
    serverHost.getNetwork().getTransports().stream()
            .filter(t -> t instanceof RelayTransport)
            .map(t -> (RelayTransport) t)
            .findFirst()
            .get()
            .setHost(serverHost);

    CompletableFuture<Void> clientStarted = clientHost.start();
    CompletableFuture<Void> serverStarted = serverHost.start();
    clientStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Client started");
    serverStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Server started");

    Multiaddr toDial =
            relayAddr.concatenated(
                    new Multiaddr("/p2p-circuit/p2p/" + serverHost.getPeerId().toBase58()));
    System.out.println("Dialling " + toDial + " from " + clientHost.getPeerId());
    StreamPromise<PingController> ping =
            clientHost
                    .getNetwork()
                    .connect(serverHost.getPeerId(), toDial)
                    .thenApply(it -> it.muxerSession().createStream(new Ping()))
                    .get(5, TimeUnit.SECONDS);

    Stream pingStream = ping.getStream().get(5, TimeUnit.SECONDS);
    System.out.println("Ping stream created");
    PingController pingCtr = ping.getController().get(5, TimeUnit.SECONDS);
    System.out.println("Ping controller created");

    for (int i = 0; i < 10; i++) {
      long latency = pingCtr.ping().get(1, TimeUnit.SECONDS);
      System.out.println("Ping is " + latency);
    }
    pingStream.close().get(5, TimeUnit.SECONDS);
    System.out.println("Ping stream closed");

    Assertions.assertThrows(
            ExecutionException.class, () -> pingCtr.ping().get(5, TimeUnit.SECONDS));

    clientHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Client stopped");
    serverHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Server stopped");
  }
}