package io.libp2p.transport.quic;

import io.libp2p.core.Host;
import io.libp2p.core.PeerId;
import io.libp2p.core.Stream;
import io.libp2p.core.StreamPromise;
import io.libp2p.core.crypto.*;
import io.libp2p.core.dsl.*;
import io.libp2p.core.multiformats.*;
import io.libp2p.core.mux.StreamMuxerProtocol;
import io.libp2p.protocol.*;
import io.libp2p.security.noise.NoiseXXSecureChannel;
import io.libp2p.security.tls.TlsSecureChannel;
import io.libp2p.transport.tcp.TcpTransport;
import io.netty.handler.logging.LogLevel;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import kotlin.*;
import org.junit.jupiter.api.*;

public class QuicServerTestJava {
  public static int getPort() {
    return new Random().nextInt(20_000) + 10_000;
  }

  @Test
  void pingJava() throws Exception {
    String localListenAddress = "/ip4/127.0.0.1/udp/" + getPort() + "/quic-v1";

    Host clientHost =
        new HostBuilder()
            //                .secureTransport(QuicTransport::Ed25519)
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::Ecdsa)
            .transport(TcpTransport::new)
            .secureChannel(TlsSecureChannel::ECDSA)
            .muxer(StreamMuxerProtocol::getYamux)
            .build();

    Host serverHost =
        new HostBuilder()
            //                .secureTransport(QuicTransport::Ed25519)
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::Ecdsa)
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
  void tlsAndQuicInSameHostPing() throws Exception {
    int port = getPort();
    String localQuicListenAddress = "/ip4/127.0.0.1/udp/" + port + "/quic-v1";
    String localTcpListenAddress = "/ip4/127.0.0.1/tcp/" + port;

    Host clientHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::Ecdsa)
            .transport(TcpTransport::new)
            .secureChannel(TlsSecureChannel::ECDSA)
            .secureChannel(NoiseXXSecureChannel::new)
            .muxer(StreamMuxerProtocol::getYamux)
            .build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::Ecdsa)
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
            .secureTransport(QuicTransport::Ecdsa)
            .builderModifier(
                b -> b.getDebug().getMuxFramesHandler().addCompactLogger(LogLevel.ERROR, "client"))
            .build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::Ecdsa)
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
        new HostBuilder().keyType(KeyType.ED25519).secureTransport(QuicTransport::Ecdsa).build();

    Host serverHost =
        new HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::Ecdsa)
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
}
