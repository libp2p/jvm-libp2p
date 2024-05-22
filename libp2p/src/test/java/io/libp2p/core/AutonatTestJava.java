package io.libp2p.core;

import io.libp2p.core.dsl.*;
import io.libp2p.core.multiformats.*;
import io.libp2p.core.mux.*;
import io.libp2p.protocol.*;
import io.libp2p.protocol.autonat.*;
import io.libp2p.protocol.autonat.pb.*;
import io.libp2p.security.noise.*;
import io.libp2p.transport.tcp.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

public class AutonatTestJava {

  @Test
  void autonatDial() throws Exception {
    Host clientHost =
        new HostBuilder()
            .transport(TcpTransport::new)
            .secureChannel(NoiseXXSecureChannel::new)
            .muxer(StreamMuxerProtocol::getYamux)
            .protocol(new Ping())
            .protocol(new AutonatProtocol.Binding())
            .listen("/ip4/127.0.0.1/tcp/0")
            .build();

    Host serverHost =
        new HostBuilder()
            .transport(TcpTransport::new)
            .secureChannel(NoiseXXSecureChannel::new)
            .muxer(StreamMuxerProtocol::getYamux)
            .protocol(new Ping())
            .protocol(new AutonatProtocol.Binding())
            .listen("/ip4/127.0.0.1/tcp/0")
            .build();

    CompletableFuture<Void> clientStarted = clientHost.start();
    CompletableFuture<Void> serverStarted = serverHost.start();
    clientStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Client started");
    serverStarted.get(5, TimeUnit.SECONDS);
    System.out.println("Server started");

    StreamPromise<AutonatProtocol.AutoNatController> autonat =
        clientHost
            .getNetwork()
            .connect(serverHost.getPeerId(), serverHost.listenAddresses().get(0))
            .thenApply(it -> it.muxerSession().createStream(new AutonatProtocol.Binding()))
            .get(5, TimeUnit.SECONDS);

    Stream autonatStream = autonat.getStream().get(5, TimeUnit.SECONDS);
    System.out.println("Autonat stream created");
    AutonatProtocol.AutoNatController autonatCtr = autonat.getController().get(5, TimeUnit.SECONDS);
    System.out.println("Autonat controller created");

    Autonat.Message.DialResponse resp =
        autonatCtr
            .requestDial(clientHost.getPeerId(), clientHost.listenAddresses())
            .get(5, TimeUnit.SECONDS);
    Assertions.assertEquals(resp.getStatus(), Autonat.Message.ResponseStatus.OK);
    Multiaddr received = Multiaddr.deserialize(resp.getAddr().toByteArray());
    Assertions.assertEquals(received, clientHost.listenAddresses().get(0));

    autonatStream.close().get(5, TimeUnit.SECONDS);
    System.out.println("Autonat stream closed");

    clientHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Client stopped");
    serverHost.stop().get(5, TimeUnit.SECONDS);
    System.out.println("Server stopped");
  }
}
