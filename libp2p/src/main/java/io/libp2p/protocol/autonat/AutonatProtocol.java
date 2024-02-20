package io.libp2p.protocol.autonat;

import com.google.protobuf.*;
import io.libp2p.core.*;
import io.libp2p.core.Stream;
import io.libp2p.core.multiformats.*;
import io.libp2p.core.multistream.*;
import io.libp2p.protocol.*;
import io.libp2p.protocol.autonat.pb.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import org.jetbrains.annotations.*;

public class AutonatProtocol extends ProtobufProtocolHandler<AutonatProtocol.AutoNatController> {

  public static class Binding extends StrictProtocolBinding<AutoNatController> {
    public Binding() {
      super("/libp2p/autonat/v1.0.0", new AutonatProtocol());
    }
  }

  public interface AutoNatController {
    CompletableFuture<Autonat.Message> rpc(Autonat.Message req);

    default CompletableFuture<Autonat.Message.DialResponse> requestDial(
        PeerId ourId, List<Multiaddr> us) {
      if (us.isEmpty())
        throw new IllegalStateException("Requested autonat dial with no addresses!");
      return rpc(Autonat.Message.newBuilder()
              .setType(Autonat.Message.MessageType.DIAL)
              .setDial(
                  Autonat.Message.Dial.newBuilder()
                      .setPeer(
                          Autonat.Message.PeerInfo.newBuilder()
                              .addAllAddrs(
                                  us.stream()
                                      .map(a -> ByteString.copyFrom(a.serialize()))
                                      .collect(Collectors.toList()))
                              .setId(ByteString.copyFrom(ourId.getBytes()))))
              .build())
          .thenApply(msg -> msg.getDialResponse());
    }
  }

  public static class Sender implements ProtocolMessageHandler<Autonat.Message>, AutoNatController {
    private final Stream stream;
    private final LinkedBlockingDeque<CompletableFuture<Autonat.Message>> queue =
        new LinkedBlockingDeque<>();

    public Sender(Stream stream) {
      this.stream = stream;
    }

    @Override
    public void onMessage(@NotNull Stream stream, Autonat.Message msg) {
      queue.poll().complete(msg);
    }

    public CompletableFuture<Autonat.Message> rpc(Autonat.Message req) {
      CompletableFuture<Autonat.Message> res = new CompletableFuture<>();
      queue.add(res);
      stream.writeAndFlush(req);
      return res;
    }
  }

  private static boolean sameIP(Multiaddr a, Multiaddr b) {
    if (a.has(Protocol.IP4))
      return a.getFirstComponent(Protocol.IP4).equals(b.getFirstComponent(Protocol.IP4));
    if (a.has(Protocol.IP6))
      return a.getFirstComponent(Protocol.IP6).equals(b.getFirstComponent(Protocol.IP6));
    return false;
  }

  private static boolean reachableIP(Multiaddr a) {
    try {
      if (a.has(Protocol.IP4))
        return InetAddress.getByName(a.getFirstComponent(Protocol.IP4).getStringValue())
            .isReachable(1000);
      if (a.has(Protocol.IP6))
        return InetAddress.getByName(a.getFirstComponent(Protocol.IP6).getStringValue())
            .isReachable(1000);
    } catch (IOException e) {
    }
    return false;
  }

  public static class Receiver
      implements ProtocolMessageHandler<Autonat.Message>, AutoNatController {
    private final Stream p2pstream;

    public Receiver(Stream p2pstream) {
      this.p2pstream = p2pstream;
    }

    @Override
    public void onMessage(@NotNull Stream stream, Autonat.Message msg) {
      switch (msg.getType()) {
        case DIAL:
          {
            Autonat.Message.Dial dial = msg.getDial();
            PeerId peerId = new PeerId(dial.getPeer().getId().toByteArray());
            List<Multiaddr> requestedDials =
                dial.getPeer().getAddrsList().stream()
                    .map(s -> Multiaddr.deserialize(s.toByteArray()))
                    .collect(Collectors.toList());
            PeerId streamPeerId = stream.remotePeerId();
            if (!peerId.equals(streamPeerId)) {
              p2pstream.close();
              return;
            }

            Multiaddr remote = stream.getConnection().remoteAddress();
            Optional<Multiaddr> reachable =
                requestedDials.stream()
                    .filter(a -> sameIP(a, remote))
                    .filter(a -> !a.has(Protocol.P2PCIRCUIT))
                    .filter(a -> reachableIP(a))
                    .findAny();
            Autonat.Message.Builder resp =
                Autonat.Message.newBuilder().setType(Autonat.Message.MessageType.DIAL_RESPONSE);
            if (reachable.isPresent()) {
              resp =
                  resp.setDialResponse(
                      Autonat.Message.DialResponse.newBuilder()
                          .setStatus(Autonat.Message.ResponseStatus.OK)
                          .setAddr(ByteString.copyFrom(reachable.get().serialize())));
            } else {
              resp =
                  resp.setDialResponse(
                      Autonat.Message.DialResponse.newBuilder()
                          .setStatus(Autonat.Message.ResponseStatus.E_DIAL_ERROR));
            }
            p2pstream.writeAndFlush(resp);
          }
        default:
          {
          }
      }
    }

    public CompletableFuture<Autonat.Message> rpc(Autonat.Message msg) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Cannot send form a receiver!"));
    }
  }

  private static final int TRAFFIC_LIMIT = 2 * 1024;

  public AutonatProtocol() {
    super(Autonat.Message.getDefaultInstance(), TRAFFIC_LIMIT, TRAFFIC_LIMIT);
  }

  @NotNull
  @Override
  protected CompletableFuture<AutoNatController> onStartInitiator(@NotNull Stream stream) {
    Sender replyPropagator = new Sender(stream);
    stream.pushHandler(replyPropagator);
    return CompletableFuture.completedFuture(replyPropagator);
  }

  @NotNull
  @Override
  protected CompletableFuture<AutoNatController> onStartResponder(@NotNull Stream stream) {
    Receiver dialer = new Receiver(stream);
    stream.pushHandler(dialer);
    return CompletableFuture.completedFuture(dialer);
  }
}
