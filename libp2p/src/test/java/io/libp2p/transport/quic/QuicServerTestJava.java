package io.libp2p.transport.quic;

import io.libp2p.core.Connection;
import io.libp2p.core.ConnectionHandler;
import io.libp2p.core.Host;
import io.libp2p.core.Libp2pException;
import io.libp2p.core.PeerId;
import io.libp2p.core.Stream;
import io.libp2p.core.StreamPromise;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.crypto.PubKey;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.mux.StreamMuxerProtocol;
import io.libp2p.protocol.Blob;
import io.libp2p.protocol.BlobController;
import io.libp2p.protocol.OneShotPing;
import io.libp2p.protocol.OneShotPingController;
import io.libp2p.protocol.Ping;
import io.libp2p.protocol.PingBinding;
import io.libp2p.protocol.PingController;
import io.libp2p.protocol.PingProtocol;
import io.libp2p.security.noise.NoiseXXSecureChannel;
import io.libp2p.security.tls.TlsSecureChannel;
import io.libp2p.transport.implementation.ConnectionOverNetty;
import io.libp2p.transport.tcp.TcpTransport;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelOutputShutdownException;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamResetException;
import io.netty.handler.logging.LogLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import kotlin.Pair;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class QuicServerTestJava {
  public static int getPort() {
    return new Random().nextInt(20_000) + 10_000;
  }

  @Test
  void pingJava() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Host clientHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .transport(TcpTransport::new)
            .secureChannel(TlsSecureChannel::ECDSA)
            .muxer(StreamMuxerProtocol::getYamux)
            .build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .transport(TcpTransport::new)
            .secureChannel(TlsSecureChannel::ECDSA)
            .muxer(StreamMuxerProtocol::getYamux)
            .protocol(new Ping())
            .listen(localListenAddress)
            .build();

    CompletableFuture<Void> clientStarted = clientHost.start();
    CompletableFuture<Void> serverStarted = serverHost.start();
    clientStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Client started " + clientHost.getPeerId());
    serverStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Server started " + serverHost.getPeerId());

    Assertions.assertEquals(0, clientHost.listenAddresses().size());
    Assertions.assertEquals(1, serverHost.listenAddresses().size());
    Assertions.assertEquals(
        localListenAddress + "/p2p/" + serverHost.getPeerId(),
        serverHost.listenAddresses().get(0).toString());
    System.out.println("Hosts running");
    Thread.sleep(2_000);

    StreamPromise<PingController> ping =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), new Multiaddr(localListenAddress))
            .thenApply(it -> it.muxerSession().createStream(new Ping(500)))
            .get(5000, TimeUnit.SECONDS);

    Stream pingStream = ping.getStream().get(5, TimeUnit.SECONDS);
    System.out.println("Ping stream created");
    CompletableFuture<PingController> controller = ping.getController();
    PingController pingCtr = controller.get(5000, TimeUnit.SECONDS);
    System.out.println("Ping controller created");
    pingStream.getConnection().localAddress();
    Multiaddr remote = pingStream.getConnection().remoteAddress();
    Assertions.assertEquals(localListenAddress, remote.toString());

    for (int i = 0; i < 1000; i++) {
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
  void checkThatRemotePeerIdCorrectForSECP256K1() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    class TestConnectionHandler implements ConnectionHandler {
      public final CompletableFuture<PeerId> remotePeerIdFuture = new CompletableFuture<>();

      @Override
      public void handleConnection(@NotNull Connection conn) {
        remotePeerIdFuture.complete(conn.secureSession().getRemoteId());
      }
    }

    TestConnectionHandler clientHandler = new TestConnectionHandler();
    TestConnectionHandler serverHandler = new TestConnectionHandler();

    Host clientHost =
        new HostBuilder()
            .keyType(KeyType.SECP256K1)
            .secureTransport(QuicTransport::ECDSA)
            .builderModifier(b -> b.getConnectionHandlers().add(clientHandler))
            .build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.SECP256K1)
            .secureTransport(QuicTransport::ECDSA)
            .transport(TcpTransport::new)
            .listen(localListenAddress)
            .builderModifier(b -> b.getConnectionHandlers().add(serverHandler))
            .build();

    clientHost.start().get(5, TimeUnit.SECONDS);
    serverHost.start().get(5, TimeUnit.SECONDS);

    clientHost.getNetwork().connect(serverHost.getPeerId(), new Multiaddr(localListenAddress));

    Assertions.assertEquals(
        serverHost.getPeerId(), clientHandler.remotePeerIdFuture.get(10, TimeUnit.SECONDS));
    Assertions.assertEquals(
        clientHost.getPeerId(), serverHandler.remotePeerIdFuture.get(10, TimeUnit.SECONDS));

    clientHost.stop().get(5, TimeUnit.SECONDS);
    serverHost.stop().get(5, TimeUnit.SECONDS);
  }

  @Disabled(
      "Requires active traffic or keep-alive pings; idle timeout without keep-alive will close"
          + " idle connections. TODO: enable once keep-alive PING frames are configured.")
  @Test
  void checkConnectionIsNotClosedByTimeout() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Host clientHost =
        new HostBuilder().keyType(KeyType.SECP256K1).secureTransport(QuicTransport::ECDSA).build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.SECP256K1)
            .secureTransport(QuicTransport::ECDSA)
            .transport(TcpTransport::new)
            .listen(localListenAddress)
            .build();

    clientHost.start().get(5, TimeUnit.SECONDS);
    serverHost.start().get(5, TimeUnit.SECONDS);

    Connection connection =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), new Multiaddr(localListenAddress))
            .get(10, TimeUnit.SECONDS);

    try {
      long s = System.currentTimeMillis();
      connection.closeFuture().get(60, TimeUnit.SECONDS);
      long t = System.currentTimeMillis() - s;
      Assertions.fail("closeFuture complete in " + t + " ms");
    } catch (TimeoutException e) {
      // expected exception: connection was not closed
    } catch (Exception e) {
      throw new RuntimeException("Unexpected exception", e);
    }

    clientHost.stop().get(5, TimeUnit.SECONDS);
    serverHost.stop().get(5, TimeUnit.SECONDS);
  }

  @Test
  void oneShotPingJava() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Host clientHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .transport(TcpTransport::new)
            .secureChannel(TlsSecureChannel::ECDSA)
            .muxer(StreamMuxerProtocol::getYamux)
            .build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .transport(TcpTransport::new)
            .secureChannel(TlsSecureChannel::ECDSA)
            .muxer(StreamMuxerProtocol::getYamux)
            .protocol(new OneShotPing())
            .listen(localListenAddress)
            .build();

    CompletableFuture<Void> clientStarted = clientHost.start();
    CompletableFuture<Void> serverStarted = serverHost.start();
    clientStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Client started " + clientHost.getPeerId());
    serverStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Server started " + serverHost.getPeerId());

    Assertions.assertEquals(0, clientHost.listenAddresses().size());
    Assertions.assertEquals(1, serverHost.listenAddresses().size());
    Assertions.assertEquals(
        localListenAddress + "/p2p/" + serverHost.getPeerId(),
        serverHost.listenAddresses().get(0).toString());
    System.out.println("Hosts running");
    Thread.sleep(2_000);

    StreamPromise<OneShotPingController> ping =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), new Multiaddr(localListenAddress))
            .thenApply(it -> it.muxerSession().createStream(new OneShotPing(500)))
            .get(5000, TimeUnit.SECONDS);

    Stream pingStream = ping.getStream().get(5, TimeUnit.SECONDS);
    System.out.println("Ping stream created");
    CompletableFuture<OneShotPingController> controller = ping.getController();
    OneShotPingController pingCtr = controller.get(5000, TimeUnit.SECONDS);
    System.out.println("Ping controller created");
    pingStream.getConnection().localAddress();
    Multiaddr remote = pingStream.getConnection().remoteAddress();
    Assertions.assertEquals(localListenAddress, remote.toString());

    long s = System.currentTimeMillis();
    pingCtr.ping().get(20, TimeUnit.SECONDS);
    long l = System.currentTimeMillis() - s;
    System.out.println("One Shot Ping is Done in " + l + " ms");

    pingStream.close().get(5, TimeUnit.SECONDS);
    System.out.println("Ping stream closed");

    clientHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Client stopped");
    serverHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Server stopped");
  }

  @Test
  void tlsAndQuicInSameHostPing() throws Exception {
    int port = getPort();
    String localQuicListenAddress = "/ip4/127.0.0.1/udp/" + port + "/quic-v1";
    String localTcpListenAddress = "/ip4/127.0.0.1/tcp/" + port;

    Host clientHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .transport(TcpTransport::new)
            .secureChannel(TlsSecureChannel::ECDSA)
            .secureChannel(NoiseXXSecureChannel::new)
            .muxer(StreamMuxerProtocol::getYamux)
            .build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .transport(TcpTransport::new)
            .secureChannel(TlsSecureChannel::ECDSA)
            .secureChannel(NoiseXXSecureChannel::new)
            .muxer(StreamMuxerProtocol::getYamux)
            .protocol(new Ping())
            .listen(localQuicListenAddress, localTcpListenAddress)
            .build();

    CompletableFuture<Void> clientStarted = clientHost.start();
    CompletableFuture<Void> serverStarted = serverHost.start();
    clientStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Client started " + clientHost.getPeerId());
    serverStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Server started " + serverHost.getPeerId());

    Assertions.assertEquals(0, clientHost.listenAddresses().size());
    Assertions.assertEquals(2, serverHost.listenAddresses().size());
    Assertions.assertEquals(
        Set.of(
            localTcpListenAddress + "/p2p/" + serverHost.getPeerId(),
            localQuicListenAddress + "/p2p/" + serverHost.getPeerId()),
        serverHost.listenAddresses().stream().map(Multiaddr::toString).collect(Collectors.toSet()));
    System.out.println("Hosts running");
    Thread.sleep(2_000);

    StreamPromise<PingController> tcpPing =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), new Multiaddr(localTcpListenAddress))
            .thenApply(it -> it.muxerSession().createStream(new Ping(500)))
            .get(5000, TimeUnit.SECONDS);

    Stream pingStream = tcpPing.getStream().get(5, TimeUnit.SECONDS);
    System.out.println("Ping stream created");
    CompletableFuture<PingController> controller = tcpPing.getController();
    PingController pingCtr = controller.get(5000, TimeUnit.SECONDS);
    System.out.println("Ping controller created");

    for (int i = 0; i < 1000; i++) {
      long latency = pingCtr.ping().get(1, TimeUnit.SECONDS);
      System.out.println("Ping is " + latency);
    }
    pingStream.close().get(5, TimeUnit.SECONDS);
    System.out.println("Ping stream closed");

    Assertions.assertThrows(
        ExecutionException.class, () -> pingCtr.ping().get(5, TimeUnit.SECONDS));

    StreamPromise<PingController> quicPing =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), new Multiaddr(localQuicListenAddress))
            .thenApply(it -> it.muxerSession().createStream(new Ping(500)))
            .get(5000, TimeUnit.SECONDS);

    Stream quicPingStream = quicPing.getStream().get(5, TimeUnit.SECONDS);
    System.out.println("Ping stream created");
    CompletableFuture<PingController> quicController = quicPing.getController();
    PingController quicPingCtr = quicController.get(5000, TimeUnit.SECONDS);
    System.out.println("Ping controller created");

    for (int i = 0; i < 1000; i++) {
      long latency = quicPingCtr.ping().get(1, TimeUnit.SECONDS);
      System.out.println("Ping is " + latency);
    }
    quicPingStream.close().get(5, TimeUnit.SECONDS);
    System.out.println("Ping stream closed");

    Assertions.assertThrows(
        ExecutionException.class, () -> quicPingCtr.ping().get(5, TimeUnit.SECONDS));

    clientHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Client stopped");
    serverHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Server stopped");
  }

  @Test
  void largeBlob() throws Exception {
    int blobSize = 1024 * 1024;
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Host clientHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .builderModifier(
                b -> b.getDebug().getMuxFramesHandler().addCompactLogger(LogLevel.ERROR, "client"))
            .build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .protocol(new Blob(blobSize))
            .listen(localListenAddress)
            .builderModifier(
                b -> b.getDebug().getMuxFramesHandler().addCompactLogger(LogLevel.ERROR, "server"))
            .build();

    CompletableFuture<Void> clientStarted = clientHost.start();
    CompletableFuture<Void> serverStarted = serverHost.start();
    clientStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Client started");
    serverStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Server started");

    Assertions.assertEquals(0, clientHost.listenAddresses().size());
    Assertions.assertEquals(1, serverHost.listenAddresses().size());
    Assertions.assertEquals(
        localListenAddress + "/p2p/" + serverHost.getPeerId(),
        serverHost.listenAddresses().get(0).toString());

    StreamPromise<BlobController> blob =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), new Multiaddr(localListenAddress))
            .thenApply(it -> it.muxerSession().createStream(new Blob(blobSize)))
            .join();

    Stream blobStream = blob.getStream().get(5, TimeUnit.SECONDS);
    System.out.println("Blob stream created");
    BlobController blobCtr = blob.getController().get(5, TimeUnit.SECONDS);
    System.out.println("Blob controller created");

    for (int i = 0; i < 10; i++) {
      long latency = blobCtr.blob().join();
      System.out.println("Blob round trip is " + latency);
    }
    blobStream.close().get(5, TimeUnit.SECONDS);
    System.out.println("Blob stream closed");

    Assertions.assertThrows(
        ExecutionException.class, () -> blobCtr.blob().get(5, TimeUnit.SECONDS));

    clientHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Client stopped");
    serverHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Server stopped");
  }

  @Test
  void startHostAddPing() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Host clientHost =
        new HostBuilder().keyType(KeyType.ED25519).secureTransport(QuicTransport::ECDSA).build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .listen(localListenAddress)
            .build();

    CompletableFuture<Void> clientStarted = clientHost.start();
    CompletableFuture<Void> serverStarted = serverHost.start();
    clientStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Client started");
    serverStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Server started");

    Assertions.assertEquals(0, clientHost.listenAddresses().size());
    Assertions.assertEquals(1, serverHost.listenAddresses().size());
    Assertions.assertEquals(
        localListenAddress + "/p2p/" + serverHost.getPeerId(),
        serverHost.listenAddresses().get(0).toString());

    serverHost.addProtocolHandler(new Ping());

    StreamPromise<PingController> ping =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), new Multiaddr(localListenAddress))
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
  void keyPairGeneration() {
    Pair<PrivKey, PubKey> pair = KeyKt.generateKeyPair(KeyType.SECP256K1);
    PeerId peerId = PeerId.fromPubKey(pair.component2());
    System.out.println("PeerId: " + peerId.toHex());
  }

  @Test
  void dialWithoutPeerIdExtractsPeerIdFromCert() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Pair<PrivKey, PubKey> serverKeyPair = KeyKt.generateKeyPair(KeyType.ED25519);
    Pair<PrivKey, PubKey> clientKeyPair = KeyKt.generateKeyPair(KeyType.ED25519);
    PeerId serverPeerId = PeerId.fromPubKey(serverKeyPair.component2());

    List<io.libp2p.core.multistream.ProtocolBinding<?>> emptyProtocols = new ArrayList<>();

    QuicTransport serverTransport = QuicTransport.ECDSA(serverKeyPair.component1(), emptyProtocols);
    QuicTransport clientTransport = QuicTransport.ECDSA(clientKeyPair.component1(), emptyProtocols);

    serverTransport.initialize();
    clientTransport.initialize();

    CompletableFuture<PeerId> serverSidePeerId = new CompletableFuture<>();
    serverTransport
        .listen(
            new Multiaddr(localListenAddress),
            conn -> serverSidePeerId.complete(conn.secureSession().getRemoteId()),
            null)
        .get(5, TimeUnit.SECONDS);
    System.out.println("Server started: " + serverPeerId);

    // Dial WITHOUT a /p2p/ component — no PeerId in the address
    Multiaddr addrWithoutPeerId = new Multiaddr(localListenAddress);
    CompletableFuture<PeerId> clientSidePeerId = new CompletableFuture<>();
    Connection connection =
        clientTransport
            .dial(
                addrWithoutPeerId,
                conn -> clientSidePeerId.complete(conn.secureSession().getRemoteId()),
                null)
            .get(10, TimeUnit.SECONDS);

    PeerId reportedRemoteId = clientSidePeerId.get(5, TimeUnit.SECONDS);
    Assertions.assertEquals(
        serverPeerId,
        reportedRemoteId,
        "PeerId extracted from TLS cert should match the server's actual PeerId");

    System.out.println("Dialed without PeerId, got remote PeerId: " + reportedRemoteId);

    serverTransport.close().get(5, TimeUnit.SECONDS);
    clientTransport.close().get(5, TimeUnit.SECONDS);
  }

  @Test
  void concurrentInboundConnections() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .listen(localListenAddress)
            .build();

    serverHost.start().get(5, TimeUnit.SECONDS);
    System.out.println("Server started: " + serverHost.getPeerId());

    int numClients = 5;
    List<Host> clientHosts = new ArrayList<>();
    List<CompletableFuture<PeerId>> remotePeerIdFutures = new ArrayList<>();

    for (int i = 0; i < numClients; i++) {
      Host clientHost =
          new HostBuilder().keyType(KeyType.ED25519).secureTransport(QuicTransport::ECDSA).build();
      clientHost.start().get(5, TimeUnit.SECONDS);
      clientHosts.add(clientHost);

      CompletableFuture<PeerId> remotePeerIdFuture =
          clientHost
              .getNetwork()
              .connect(serverHost.getPeerId(), new Multiaddr(localListenAddress))
              .thenApply(conn -> conn.secureSession().getRemoteId());
      remotePeerIdFutures.add(remotePeerIdFuture);
    }

    CompletableFuture<Void> allConnected =
        CompletableFuture.allOf(remotePeerIdFutures.toArray(new CompletableFuture[0]));
    allConnected.get(15, TimeUnit.SECONDS);

    for (int i = 0; i < numClients; i++) {
      PeerId reportedRemoteId = remotePeerIdFutures.get(i).get();
      Assertions.assertEquals(
          serverHost.getPeerId(),
          reportedRemoteId,
          "Client " + i + " should report the correct server PeerId");
    }
    System.out.println("All " + numClients + " concurrent connections reported correct PeerId");

    for (Host clientHost : clientHosts) {
      clientHost.stop().get(5, TimeUnit.SECONDS);
    }
    serverHost.stop().get(5, TimeUnit.SECONDS);
  }

  /**
   * Reproduces the broken outbound dial path: when the dialing transport ALSO has an active QUIC
   * listener (as every real node does, e.g. Teku), {@code dial()} takes the port-reuse branch
   * instead of the clean ephemeral path that all the other tests exercise. This must still complete
   * a working outbound connection.
   */
  @Test
  void dialWhileListeningCompletes() throws Exception {
    String serverListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";
    String dialerListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Pair<PrivKey, PubKey> serverKeyPair = KeyKt.generateKeyPair(KeyType.ED25519);
    Pair<PrivKey, PubKey> dialerKeyPair = KeyKt.generateKeyPair(KeyType.ED25519);
    PeerId serverPeerId = PeerId.fromPubKey(serverKeyPair.component2());

    List<io.libp2p.core.multistream.ProtocolBinding<?>> emptyProtocols = new ArrayList<>();

    QuicTransport serverTransport = QuicTransport.ECDSA(serverKeyPair.component1(), emptyProtocols);
    QuicTransport dialerTransport = QuicTransport.ECDSA(dialerKeyPair.component1(), emptyProtocols);
    serverTransport.initialize();
    dialerTransport.initialize();

    CompletableFuture<PeerId> serverSidePeerId = new CompletableFuture<>();
    serverTransport
        .listen(
            new Multiaddr(serverListenAddress),
            conn -> serverSidePeerId.complete(conn.secureSession().getRemoteId()),
            null)
        .get(5, TimeUnit.SECONDS);

    // The dialer is ALSO listening on QUIC — this is what triggers the broken port-reuse branch.
    dialerTransport
        .listen(new Multiaddr(dialerListenAddress), conn -> {}, null)
        .get(5, TimeUnit.SECONDS);

    CompletableFuture<PeerId> dialerSidePeerId = new CompletableFuture<>();
    Connection connection =
        dialerTransport
            .dial(
                new Multiaddr(serverListenAddress),
                conn -> dialerSidePeerId.complete(conn.secureSession().getRemoteId()),
                null)
            .get(10, TimeUnit.SECONDS);

    Assertions.assertEquals(
        serverPeerId,
        dialerSidePeerId.get(5, TimeUnit.SECONDS),
        "Outbound dial from a listening transport must complete the QUIC handshake");
    Assertions.assertEquals(serverPeerId, connection.secureSession().getRemoteId());

    dialerTransport.close().get(5, TimeUnit.SECONDS);
    serverTransport.close().get(5, TimeUnit.SECONDS);
  }

  /**
   * Closing the write side of a stream whose connection was already closed must not fail. Mirrors
   * Teku's Goodbye flow: the peer is disconnected (closing the QUIC connection and all its
   * streams), then the RPC layer half-closes the stream it was responding on. The muxer-based
   * transports tolerate this silently; QUIC must too instead of failing with
   * "ChannelOutputShutdownException: Fin was sent already".
   */
  @Test
  void closeWriteAfterConnectionCloseSucceeds() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Host clientHost =
        new HostBuilder().keyType(KeyType.ED25519).secureTransport(QuicTransport::ECDSA).build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .protocol(new Ping())
            .listen(localListenAddress)
            .build();

    clientHost.start().get(5, TimeUnit.SECONDS);
    serverHost.start().get(5, TimeUnit.SECONDS);

    Connection connection =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), new Multiaddr(localListenAddress))
            .get(10, TimeUnit.SECONDS);

    StreamPromise<PingController> ping = connection.muxerSession().createStream(new Ping());
    Stream pingStream = ping.getStream().get(5, TimeUnit.SECONDS);
    ping.getController().get(5, TimeUnit.SECONDS);

    connection.close().get(5, TimeUnit.SECONDS);
    pingStream.closeFuture().get(5, TimeUnit.SECONDS);

    // Must complete without exception even though the stream is already gone
    pingStream.closeWrite().get(5, TimeUnit.SECONDS);

    clientHost.stop().get(5, TimeUnit.SECONDS);
    serverHost.stop().get(5, TimeUnit.SECONDS);
  }

  /**
   * A remote STREAM_RESET must close the local stream quietly, matching the muxer-based transports
   * where a remote RST closes the child channel (see AbstractMuxHandler.onRemoteClose) without
   * surfacing an exception to application handlers. Without this, Netty fires
   * QuicStreamResetException into the app pipeline (Teku logs "Unhandled error while processes
   * req/response") and leaves the stream channel open.
   */
  @Test
  void remoteStreamResetClosesStreamQuietly() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    List<Throwable> serverStreamExceptions = new CopyOnWriteArrayList<>();
    CompletableFuture<Stream> serverStreamFuture = new CompletableFuture<>();
    // Stream visitors are not wired into the QUIC transport, so capture the server-side
    // stream (and any exception reaching application handlers) via the protocol handler
    PingProtocol capturingPing =
        new PingProtocol(32) {
          @Override
          protected CompletableFuture<PingController> onStartResponder(@NotNull Stream stream) {
            stream.pushHandler(
                new ChannelInboundHandlerAdapter() {
                  @Override
                  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    serverStreamExceptions.add(cause);
                    ctx.fireExceptionCaught(cause);
                  }
                });
            serverStreamFuture.complete(stream);
            return super.onStartResponder(stream);
          }
        };

    Host clientHost =
        new HostBuilder().keyType(KeyType.ED25519).secureTransport(QuicTransport::ECDSA).build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .protocol(new PingBinding(capturingPing))
            .listen(localListenAddress)
            .build();

    clientHost.start().get(5, TimeUnit.SECONDS);
    serverHost.start().get(5, TimeUnit.SECONDS);

    Connection connection =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), new Multiaddr(localListenAddress))
            .get(10, TimeUnit.SECONDS);

    StreamPromise<PingController> ping = connection.muxerSession().createStream(new Ping());
    Stream pingStream = ping.getStream().get(5, TimeUnit.SECONDS);
    PingController pingCtr = ping.getController().get(5, TimeUnit.SECONDS);
    pingCtr.ping().get(5, TimeUnit.SECONDS);

    Stream serverStream = serverStreamFuture.get(5, TimeUnit.SECONDS);

    // Reset the stream from the client side: quiche sends a RESET_STREAM frame
    ((QuicStream) pingStream).getQuicStreamChannel().shutdownOutput(1).sync();

    serverStream.closeFuture().get(5, TimeUnit.SECONDS);
    Assertions.assertTrue(
        serverStreamExceptions.stream().noneMatch(t -> t instanceof QuicStreamResetException),
        "Remote stream reset must not surface QuicStreamResetException to application handlers,"
            + " got: "
            + serverStreamExceptions);

    clientHost.stop().get(5, TimeUnit.SECONDS);
    serverHost.stop().get(5, TimeUnit.SECONDS);
  }

  /**
   * A remote STOP_SENDING frame (the peer no longer wants to read from the stream) must not surface
   * to application handlers. Netty fails our queued/in-flight writes with a
   * ChannelOutputShutdownException("STOP_SENDING frame received"); without special handling that
   * exception travels the stream pipeline's exceptionCaught path and reaches the application's
   * ProtocolMessageHandler.onException (Teku logs "Unhandled error while processing req/response").
   * QuicStreamReadCloseEventConverter must swallow it quietly and leave the stream open, since only
   * the write side is affected.
   */
  @Test
  void remoteStopSendingDoesNotSurfaceToApplicationHandlers() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    CompletableFuture<Stream> serverStreamFuture = new CompletableFuture<>();
    PingProtocol capturingPing =
        new PingProtocol(32) {
          @Override
          protected CompletableFuture<PingController> onStartResponder(@NotNull Stream stream) {
            serverStreamFuture.complete(stream);
            return super.onStartResponder(stream);
          }
        };

    Host clientHost =
        new HostBuilder().keyType(KeyType.ED25519).secureTransport(QuicTransport::ECDSA).build();
    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .protocol(new PingBinding(capturingPing))
            .listen(localListenAddress)
            .build();

    clientHost.start().get(5, TimeUnit.SECONDS);
    serverHost.start().get(5, TimeUnit.SECONDS);

    Connection connection =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), new Multiaddr(localListenAddress))
            .get(10, TimeUnit.SECONDS);

    StreamPromise<PingController> ping = connection.muxerSession().createStream(new Ping());
    Stream pingStream = ping.getStream().get(5, TimeUnit.SECONDS);
    ping.getController().get(5, TimeUnit.SECONDS);

    QuicStreamChannel clientStreamChannel = ((QuicStream) pingStream).getQuicStreamChannel();

    // Capture exceptions that get past the converter towards application handlers. Insert the
    // capturer immediately after QuicStreamReadCloseEventConverter so the converter runs first:
    // if it swallows the exception (the fix) nothing reaches the capturer; if it forwards it (the
    // bug) the capturer records it, exactly where the application handler would otherwise see it.
    List<Throwable> appExceptions = new CopyOnWriteArrayList<>();
    String converterName =
        clientStreamChannel.pipeline().context(QuicStreamReadCloseEventConverter.class).name();
    clientStreamChannel
        .pipeline()
        .addAfter(
            converterName,
            "test-app-exception-capture",
            new ChannelInboundHandlerAdapter() {
              @Override
              public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                appExceptions.add(cause);
                ctx.fireExceptionCaught(cause);
              }
            });

    Stream serverStream = serverStreamFuture.get(5, TimeUnit.SECONDS);

    // The server stops reading: quiche sends a STOP_SENDING frame to the client.
    ((QuicStream) serverStream).getQuicStreamChannel().shutdownInput().sync();

    // Write to the client stream after STOP_SENDING. Use a void promise so a write failure is
    // routed through the pipeline's exceptionCaught (mirroring how the exception surfaced in the
    // wild), rather than only failing the write future. quiche reports STREAM_STOPPED and Netty
    // fails the write with ChannelOutputShutdownException.
    for (int i = 0; i < 40 && clientStreamChannel.isOpen(); i++) {
      clientStreamChannel.writeAndFlush(
          Unpooled.wrappedBuffer(new byte[256]), clientStreamChannel.voidPromise());
      Thread.sleep(25);
    }

    // Allow any fired exception to propagate through the pipeline before asserting.
    Thread.sleep(500);

    Assertions.assertTrue(
        appExceptions.stream().noneMatch(t -> t instanceof ChannelOutputShutdownException),
        "Remote STOP_SENDING must not surface ChannelOutputShutdownException to application"
            + " handlers, got: "
            + appExceptions);
    Assertions.assertTrue(
        clientStreamChannel.isOpen(),
        "STOP_SENDING only stops the write side; the stream must stay open for reads");

    clientHost.stop().get(5, TimeUnit.SECONDS);
    serverHost.stop().get(5, TimeUnit.SECONDS);
  }

  /** Calling closeWrite twice must be idempotent: the first call already sent the FIN. */
  @Test
  void closeWriteIsIdempotent() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Host clientHost =
        new HostBuilder().keyType(KeyType.ED25519).secureTransport(QuicTransport::ECDSA).build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .protocol(new Ping())
            .listen(localListenAddress)
            .build();

    clientHost.start().get(5, TimeUnit.SECONDS);
    serverHost.start().get(5, TimeUnit.SECONDS);

    Connection connection =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), new Multiaddr(localListenAddress))
            .get(10, TimeUnit.SECONDS);

    StreamPromise<PingController> ping = connection.muxerSession().createStream(new Ping());
    Stream pingStream = ping.getStream().get(5, TimeUnit.SECONDS);
    ping.getController().get(5, TimeUnit.SECONDS);

    pingStream.closeWrite().get(5, TimeUnit.SECONDS);
    pingStream.closeWrite().get(5, TimeUnit.SECONDS);

    clientHost.stop().get(5, TimeUnit.SECONDS);
    serverHost.stop().get(5, TimeUnit.SECONDS);
  }

  /**
   * Verify that dialAsListener times out when no peer connects back. This tests the timeout path
   * without requiring a real hole punch.
   */
  @Test
  void holePunchTimeout() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Pair<PrivKey, PubKey> serverKeyPair = KeyKt.generateKeyPair(KeyType.ED25519);
    List<io.libp2p.core.multistream.ProtocolBinding<?>> emptyProtocols = new ArrayList<>();

    QuicTransport transport = QuicTransport.ECDSA(serverKeyPair.component1(), emptyProtocols);
    transport.initialize();
    transport.listen(new Multiaddr(localListenAddress), conn -> {}, null).get(5, TimeUnit.SECONDS);
    System.out.println("Transport listening on: " + localListenAddress);

    // Dial a non-existent peer at an address where nobody will connect back
    // Use a different port so nobody is listening there. A hole punch always targets a known
    // peer, so the address must carry a /p2p peer id (otherwise dialAsListener fails fast).
    int unreachablePort = getPort();
    Multiaddr unreachableAddr =
        new Multiaddr(
            "/ip4/127.0.0.1/udp/" + unreachablePort + "/quic-v1/p2p/" + PeerId.random().toBase58());

    CompletableFuture<Connection> holePunchFuture =
        transport.dialAsListener(unreachableAddr, conn -> {}, null);

    long start = System.currentTimeMillis();
    try {
      holePunchFuture.get(6, TimeUnit.SECONDS);
      Assertions.fail("Expected hole punch to time out, but it completed successfully");
    } catch (ExecutionException e) {
      long elapsed = System.currentTimeMillis() - start;
      System.out.println("Hole punch failed as expected after " + elapsed + "ms: " + e.getCause());
      // Expected: the future completes exceptionally (TimeoutException wrapped in
      // ExecutionException)
      Assertions.assertInstanceOf(
          java.util.concurrent.TimeoutException.class,
          e.getCause(),
          "Expected TimeoutException but got: " + e.getCause());
    }

    transport.close().get(5, TimeUnit.SECONDS);
  }

  /**
   * A hole punch always targets a known peer. dialAsListener must reject an address with no {@code
   * /p2p} peer id, because without it the inbound connection could not be validated before exposure
   * — any peer reaching the expected UDP tuple would be handed back as the dialed peer.
   */
  @Test
  void holePunchWithoutPeerIdIsRejected() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Pair<PrivKey, PubKey> serverKeyPair = KeyKt.generateKeyPair(KeyType.ED25519);
    List<io.libp2p.core.multistream.ProtocolBinding<?>> emptyProtocols = new ArrayList<>();

    QuicTransport transport = QuicTransport.ECDSA(serverKeyPair.component1(), emptyProtocols);
    transport.initialize();
    transport.listen(new Multiaddr(localListenAddress), conn -> {}, null).get(5, TimeUnit.SECONDS);

    Multiaddr noPeerIdAddr = new Multiaddr("/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1");

    Assertions.assertThrows(
        Libp2pException.class,
        () -> transport.dialAsListener(noPeerIdAddr, conn -> {}, null),
        "dialAsListener must reject a hole-punch target with no /p2p peer id");

    transport.close().get(5, TimeUnit.SECONDS);
  }

  /**
   * Each outbound {@code dial()} binds its own ephemeral {@code NioDatagramChannel} to carry the
   * QUIC connection. When the QUIC connection closes, that underlying datagram channel must be
   * closed too — closing a {@code QuicChannel} does not close its parent datagram channel in Netty.
   * If it is left open, every dial leaks a datagram channel (plus its codec, native quiche config
   * and queued direct buffers) for the lifetime of the transport.
   */
  @Test
  void dialClosesUnderlyingDatagramChannelWhenConnectionCloses() throws Exception {
    String serverListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Pair<PrivKey, PubKey> serverKeyPair = KeyKt.generateKeyPair(KeyType.ED25519);
    Pair<PrivKey, PubKey> clientKeyPair = KeyKt.generateKeyPair(KeyType.ED25519);
    List<io.libp2p.core.multistream.ProtocolBinding<?>> emptyProtocols = new ArrayList<>();

    QuicTransport serverTransport = QuicTransport.ECDSA(serverKeyPair.component1(), emptyProtocols);
    QuicTransport clientTransport = QuicTransport.ECDSA(clientKeyPair.component1(), emptyProtocols);
    serverTransport.initialize();
    clientTransport.initialize();

    serverTransport
        .listen(new Multiaddr(serverListenAddress), conn -> {}, null)
        .get(5, TimeUnit.SECONDS);

    Connection connection =
        clientTransport
            .dial(new Multiaddr(serverListenAddress), conn -> {}, null)
            .get(10, TimeUnit.SECONDS);

    Channel quicChannel = ((ConnectionOverNetty) connection).getNettyChannel();
    Channel datagramChannel = quicChannel.parent();
    Assertions.assertNotNull(
        datagramChannel, "QUIC connection should ride on an underlying datagram channel");
    Assertions.assertTrue(
        datagramChannel.isOpen(), "datagram channel should be open while the connection is live");

    // Closing the QUIC connection must cascade to the ephemeral dial datagram channel.
    connection.close().get(5, TimeUnit.SECONDS);

    datagramChannel.closeFuture().get(5, TimeUnit.SECONDS);
    Assertions.assertFalse(
        datagramChannel.isOpen(),
        "underlying datagram channel must be closed after the QUIC connection closes");

    clientTransport.close().get(5, TimeUnit.SECONDS);
    serverTransport.close().get(5, TimeUnit.SECONDS);
  }

  /**
   * A dial that fails AFTER the QUIC handshake completes (e.g. the connection handler throws during
   * setup) must close the already-established QuicChannel rather than leave it registered and open.
   * Otherwise the failed dial leaks the QUIC connection and its underlying datagram channel for the
   * lifetime of the transport.
   */
  @Test
  void dialThatFailsAfterHandshakeDoesNotLeakConnection() throws Exception {
    String serverListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Pair<PrivKey, PubKey> serverKeyPair = KeyKt.generateKeyPair(KeyType.ED25519);
    Pair<PrivKey, PubKey> clientKeyPair = KeyKt.generateKeyPair(KeyType.ED25519);
    List<io.libp2p.core.multistream.ProtocolBinding<?>> emptyProtocols = new ArrayList<>();

    QuicTransport serverTransport = QuicTransport.ECDSA(serverKeyPair.component1(), emptyProtocols);
    QuicTransport clientTransport = QuicTransport.ECDSA(clientKeyPair.component1(), emptyProtocols);
    serverTransport.initialize();
    clientTransport.initialize();

    serverTransport
        .listen(new Multiaddr(serverListenAddress), conn -> {}, null)
        .get(5, TimeUnit.SECONDS);

    // Connection handler that throws after the handshake completes, exercising the post-handshake
    // failure path in dial().thenApply.
    ConnectionHandler throwingHandler =
        conn -> {
          throw new RuntimeException("boom");
        };

    CompletableFuture<Connection> dial =
        clientTransport.dial(new Multiaddr(serverListenAddress), throwingHandler, null);

    Assertions.assertThrows(ExecutionException.class, () -> dial.get(10, TimeUnit.SECONDS));

    // The failed dial must not leak the established QuicChannel.
    long deadline = System.currentTimeMillis() + 5_000;
    while (clientTransport.getActiveConnections() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    Assertions.assertEquals(
        0,
        clientTransport.getActiveConnections(),
        "a dial that fails after the handshake must close (not leak) the QUIC connection");

    clientTransport.close().get(5, TimeUnit.SECONDS);
    serverTransport.close().get(5, TimeUnit.SECONDS);
  }

  /**
   * NetworkImpl.connect() races dials across multiple addresses and cancels the losing dial futures
   * once the first one completes. For QUIC, dial() returns the dependent {@code thenApply} future;
   * cancelling it does not propagate upstream to the QUIC connect. If the handshake then completes,
   * the {@code thenApply} body is skipped, so the established QuicChannel is never registered,
   * handed to the caller, nor closed — it (and its underlying datagram channel) leaks until the
   * idle timeout. A cancelled dial must promptly close the established QUIC connection.
   */
  @Test
  void cancelledDialClosesEstablishedQuicConnection() throws Exception {
    String serverListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Pair<PrivKey, PubKey> serverKeyPair = KeyKt.generateKeyPair(KeyType.ED25519);
    Pair<PrivKey, PubKey> clientKeyPair = KeyKt.generateKeyPair(KeyType.ED25519);
    List<io.libp2p.core.multistream.ProtocolBinding<?>> emptyProtocols = new ArrayList<>();

    QuicTransport serverTransport = QuicTransport.ECDSA(serverKeyPair.component1(), emptyProtocols);
    QuicTransport clientTransport = QuicTransport.ECDSA(clientKeyPair.component1(), emptyProtocols);
    serverTransport.initialize();
    clientTransport.initialize();

    serverTransport
        .listen(new Multiaddr(serverListenAddress), conn -> {}, null)
        .get(5, TimeUnit.SECONDS);

    CompletableFuture<Connection> dial =
        clientTransport.dial(new Multiaddr(serverListenAddress), conn -> {}, null);
    // Cancel synchronously, before the async handshake completes — the same situation a losing dial
    // in NetworkImpl.connect() hits.
    dial.cancel(true);

    // The handshake still completes at the network level, so the QuicChannel is established and
    // tracked. A leaked channel stays in activeConnections until the 30s idle timeout; a properly
    // handled cancellation closes it, dropping activeConnections back to zero well inside this
    // window.
    long deadline = System.currentTimeMillis() + 15_000;
    while (clientTransport.getActiveConnections() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    Assertions.assertEquals(
        0,
        clientTransport.getActiveConnections(),
        "a cancelled dial must close (not leak) the established QUIC connection");

    clientTransport.close().get(5, TimeUnit.SECONDS);
    serverTransport.close().get(5, TimeUnit.SECONDS);
  }
}
