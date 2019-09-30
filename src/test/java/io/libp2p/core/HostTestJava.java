package io.libp2p.core;

import io.libp2p.core.crypto.KEY_TYPE;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.crypto.PubKey;
import io.libp2p.core.dsl.BuildersJKt;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multistream.Multistream;
import io.libp2p.core.multistream.ProtocolBinding;
import io.libp2p.core.multistream.ProtocolMatcher;
import io.libp2p.host.HostImpl;
import io.libp2p.mux.mplex.MplexStreamMuxer;
import io.libp2p.protocol.Ping;
import io.libp2p.protocol.PingController;
import io.libp2p.security.secio.SecIoSecureChannel;
import io.libp2p.transport.tcp.TcpTransport;
import io.netty.handler.logging.LogLevel;
import kotlin.Pair;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class HostTestJava {

    static class A implements ProtocolBinding<String> {
        @NotNull
        @Override
        public String getAnnounce() {
            return null;
        }

        @NotNull
        @Override
        public ProtocolMatcher getMatcher() {
            return null;
        }

        @NotNull
        @Override
        public CompletableFuture<? extends String> initChannel(@NotNull P2PAbstractChannel ch, @NotNull String selectedProtocol) {
            this.toInitiator("aa");
            return null;
        }

    }

    @Test
    public void test1() throws Exception {
        HostImpl host1 = BuildersJKt.hostJ(b -> {
            b.getIdentity().random();
            b.getTransports().add(TcpTransport::new);
            b.getSecureChannels().add(SecIoSecureChannel::new);
            b.getMuxers().add(MplexStreamMuxer::new);
            b.getProtocols().add(new Ping());
            b.getDebug().getMuxFramesHandler().setLogger(LogLevel.ERROR, "host-1-MUX");
            b.getDebug().getBeforeSecureHandler().setLogger(LogLevel.ERROR, "host-1-BS");
            b.getDebug().getAfterSecureHandler().setLogger(LogLevel.ERROR, "host-1-AS");
        });

        HostImpl host2 = BuildersJKt.hostJ(b -> {
            b.getIdentity().random();
            b.getTransports().add(TcpTransport::new);
            b.getSecureChannels().add(SecIoSecureChannel::new);
            b.getMuxers().add(MplexStreamMuxer::new);
            b.getProtocols().add(new Ping());
            b.getNetwork().listen("/ip4/0.0.0.0/tcp/40002");
        });

        CompletableFuture<Unit> start1 = host1.start();
        CompletableFuture<Unit> start2 = host2.start();
        start1.get(5, TimeUnit.SECONDS);
        System.out.println("Host #1 started");
        start2.get(5, TimeUnit.SECONDS);
        System.out.println("Host #2 started");

        StreamPromise<PingController> ping = host1.getNetwork().connect(host2.getPeerId(), new Multiaddr("/ip4/127.0.0.1/tcp/40002"))
                .thenApply(it -> it.getMuxerSession().createStream(Multistream.create(new Ping()).toStreamHandler()))
                .get(5, TimeUnit.SECONDS);
        Stream pingStream = ping.getStream().get(5, TimeUnit.SECONDS);
        System.out.println("Ping stream created");
        PingController pingCtr = ping.getController().get(5, TimeUnit.SECONDS);
        System.out.println("Ping controller created");

        for (int i = 0; i < 10; i++) {
            long latency = pingCtr.ping().get(1, TimeUnit.SECONDS);
            System.out.println("Ping is " + latency);
        }
        pingStream.getNettyChannel().close().await(5, TimeUnit.SECONDS);
        System.out.println("Ping stream closed");

        Assertions.assertThrows(ExecutionException.class, () ->
                pingCtr.ping().get(5, TimeUnit.SECONDS));

        host1.stop().get(5, TimeUnit.SECONDS);
        System.out.println("Host #1 stopped");
        host2.stop().get(5, TimeUnit.SECONDS);
        System.out.println("Host #2 stopped");
    }

    @Test
    void test2() {
        Pair<PrivKey, PubKey> pair = KeyKt.generateKeyPair(KEY_TYPE.SECP256K1);
        PeerId peerId = PeerId.fromPubKey(pair.component2());
        System.out.println("PeerId: " + peerId.toHex());

    }
}
