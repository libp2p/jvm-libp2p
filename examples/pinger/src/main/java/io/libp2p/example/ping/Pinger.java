package io.libp2p.example.ping;

import io.libp2p.core.Host;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.protocol.Ping;
import io.libp2p.protocol.PingController;
import java.util.concurrent.ExecutionException;

public class Pinger {
  public static void main(String[] args) throws ExecutionException, InterruptedException {
    // Create a libp2p node and configure it
    // to accept TCP connections on a random port
    Host node = new HostBuilder().protocol(new Ping()).listen("/ip4/127.0.0.1/tcp/0").build();

    // start listening
    node.start().get();

    System.out.print("Node started and listening on ");
    System.out.println(node.listenAddresses());

    if (args.length > 0) {
      Multiaddr address = Multiaddr.fromString(args[0]);
      PingController pinger = new Ping().dial(node, address).getController().get();

      System.out.println("Sending 5 ping messages to " + address.toString());
      for (int i = 1; i <= 5; ++i) {
        long latency = pinger.ping().get();
        System.out.println("Ping " + i + ", latency " + latency + "ms");
      }

      node.stop().get();
    }
  }
}
