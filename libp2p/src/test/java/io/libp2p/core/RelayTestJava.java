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
    b.getTransports()
        .add(
            u -> new RelayTransport(hop, stop, u, h -> relays, new ScheduledThreadPoolExecutor(1)));
  }

  @Test
  void pingOverLocalRelay() throws Exception {
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
            .listen("/ip4/127.0.0.1/tcp/0")
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
  void relayStreamsAreLimited() throws Exception {
    Host relayHost =
        new HostBuilder()
            .builderModifier(b -> enableRelay(b, Collections.emptyList()))
            .transport(TcpTransport::new)
            .secureChannel(NoiseXXSecureChannel::new)
            .muxer(StreamMuxerProtocol::getYamux)
            .listen("/ip4/127.0.0.1/tcp/0")
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

    // Relay streams are limited to 4096 bytes in either direction
    // This is the smallest value that triggers the limit
    // not sure why there is so much overhead from 3 * multistream + noise + yamux!
    int blobSize = 1469;
    Host clientHost =
        new HostBuilder()
            .builderModifier(b -> enableRelay(b, relays))
            .transport(TcpTransport::new)
            .secureChannel(NoiseXXSecureChannel::new)
            .muxer(StreamMuxerProtocol::getYamux)
            .protocol(new Blob(blobSize))
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
            .protocol(new Blob(blobSize))
            .listen("/ip4/127.0.0.1/tcp/0")
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
    StreamPromise<BlobController> blob =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), toDial)
            .thenApply(it -> it.muxerSession().createStream(new Blob(blobSize)))
            .get(5, TimeUnit.SECONDS);

    Stream blobStream = blob.getStream().get(5, TimeUnit.SECONDS);
    System.out.println("Blob stream created");
    BlobController blobCtr = blob.getController().get(5, TimeUnit.SECONDS);
    System.out.println("Blob controller created");

    Assertions.assertThrows(
        ExecutionException.class, () -> blobCtr.blob().get(5, TimeUnit.SECONDS));

    clientHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Client stopped");
    serverHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Server stopped");
  }
}
