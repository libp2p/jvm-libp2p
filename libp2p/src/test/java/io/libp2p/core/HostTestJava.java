package io.libp2p.core;

import io.libp2p.core.crypto.KEY_TYPE;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.crypto.PubKey;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.mux.StreamMuxerProtocol;
import io.libp2p.protocol.Ping;
import io.libp2p.protocol.PingController;
import io.libp2p.security.tls.*;
import io.libp2p.transport.tcp.TcpTransport;
import kotlin.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class HostTestJava {
    @Test
    void ping() throws Exception {
/*        HostImpl clientHost = BuildersJKt.hostJ(b -> {
            b.getIdentity().random();
            b.getTransports().add(TcpTransport::new);
            b.getSecureChannels().add(SecIoSecureChannel::new);
            b.getMuxers().add(MplexStreamMuxer::new);
            b.getProtocols().add(new Ping());
            b.getDebug().getMuxFramesHandler().setLogger(LogLevel.ERROR, "host-1-MUX");
            b.getDebug().getBeforeSecureHandler().setLogger(LogLevel.ERROR, "host-1-BS");
            b.getDebug().getAfterSecureHandler().setLogger(LogLevel.ERROR, "host-1-AS");
        });
*/
        String localListenAddress = "/ip4/127.0.0.1/tcp/40002";

        Host clientHost = new HostBuilder()
                .transport(TcpTransport::new)
                .secureChannel((k, m) -> new TlsSecureChannel(k, m, "ECDSA"))
                .muxer(StreamMuxerProtocol::getYamux)
                .build();

        Host serverHost = new HostBuilder()
                .transport(TcpTransport::new)
                .secureChannel(TlsSecureChannel::new)
                .muxer(StreamMuxerProtocol::getYamux)
                .protocol(new Ping())
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
                serverHost.listenAddresses().get(0).toString()
        );

        StreamPromise<PingController> ping =
                clientHost.getNetwork().connect(
                        serverHost.getPeerId(),
                        new Multiaddr(localListenAddress)
                ).thenApply(
                        it -> it.muxerSession().createStream(new Ping())
                )
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

        Assertions.assertThrows(ExecutionException.class, () ->
                pingCtr.ping().get(5, TimeUnit.SECONDS));

        clientHost.stop().get(5, TimeUnit.SECONDS);
        System.out.println("Client stopped");
        serverHost.stop().get(5, TimeUnit.SECONDS);
        System.out.println("Server stopped");
    }

    @Test
    void largePing() throws Exception {
        int pingSize = 200 * 1024;
        String localListenAddress = "/ip4/127.0.0.1/tcp/40002";

        Host clientHost = new HostBuilder()
                .transport(TcpTransport::new)
                .secureChannel((k, m) -> new TlsSecureChannel(k, m, "ECDSA"))
                .muxer(StreamMuxerProtocol::getYamux)
                .build();

        Host serverHost = new HostBuilder()
                .transport(TcpTransport::new)
                .secureChannel(TlsSecureChannel::new)
                .muxer(StreamMuxerProtocol::getYamux)
                .protocol(new Ping(pingSize))
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
                serverHost.listenAddresses().get(0).toString()
        );

        StreamPromise<PingController> ping =
                clientHost.getNetwork().connect(
                                serverHost.getPeerId(),
                                new Multiaddr(localListenAddress)
                        ).thenApply(
                                it -> it.muxerSession().createStream(new Ping(pingSize))
                        )
                        .join();

        Stream pingStream = ping.getStream().get(5, TimeUnit.SECONDS);
        System.out.println("Ping stream created");
        PingController pingCtr = ping.getController().get(5, TimeUnit.SECONDS);
        System.out.println("Ping controller created");

        for (int i = 0; i < 10; i++) {
            long latency = pingCtr.ping().join();//get(5, TimeUnit.SECONDS);
            System.out.println("Ping is " + latency);
        }
        pingStream.close().get(5, TimeUnit.SECONDS);
        System.out.println("Ping stream closed");

        Assertions.assertThrows(ExecutionException.class, () ->
                pingCtr.ping().get(5, TimeUnit.SECONDS));

        clientHost.stop().get(5, TimeUnit.SECONDS);
        System.out.println("Client stopped");
        serverHost.stop().get(5, TimeUnit.SECONDS);
        System.out.println("Server stopped");
    }

    @Test
    void addPingAfterHostStart() throws Exception {
        String localListenAddress = "/ip4/127.0.0.1/tcp/40002";

        Host clientHost = new HostBuilder()
                .transport(TcpTransport::new)
                .secureChannel((k, m) -> new TlsSecureChannel(k, m, "ECDSA"))
                .muxer(StreamMuxerProtocol::getYamux)
                .build();

        Host serverHost = new HostBuilder()
                .transport(TcpTransport::new)
                .secureChannel(TlsSecureChannel::new)
                .muxer(StreamMuxerProtocol::getYamux)
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
                serverHost.listenAddresses().get(0).toString()
        );

        serverHost.addProtocolHandler(new Ping());

        StreamPromise<PingController> ping =
                clientHost.getNetwork().connect(
                                serverHost.getPeerId(),
                                new Multiaddr(localListenAddress)
                        ).thenApply(
                                it -> it.muxerSession().createStream(new Ping())
                        )
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

        Assertions.assertThrows(ExecutionException.class, () ->
                pingCtr.ping().get(5, TimeUnit.SECONDS));

        clientHost.stop().get(5, TimeUnit.SECONDS);
        System.out.println("Client stopped");
        serverHost.stop().get(5, TimeUnit.SECONDS);
        System.out.println("Server stopped");
    }

    @Test
    void keyPairGeneration() {
        Pair<PrivKey, PubKey> pair = KeyKt.generateKeyPair(KEY_TYPE.SECP256K1);
        PeerId peerId = PeerId.fromPubKey(pair.component2());
        System.out.println("PeerId: " + peerId.toHex());
    }
}
