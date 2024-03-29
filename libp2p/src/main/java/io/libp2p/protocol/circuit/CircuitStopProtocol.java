package io.libp2p.protocol.circuit;

import com.google.protobuf.*;
import io.libp2p.core.*;
import io.libp2p.core.multistream.*;
import io.libp2p.protocol.*;
import io.libp2p.protocol.circuit.pb.*;
import io.netty.channel.*;
import io.netty.handler.codec.protobuf.*;
import java.util.concurrent.*;
import org.jetbrains.annotations.*;

public class CircuitStopProtocol
    extends ProtobufProtocolHandler<CircuitStopProtocol.StopController> {

  private static final String STOP_HANDLER_NAME = "STOP_HANDLER";
  public static final String STOP_REMOVER_NAME = "STOP_REMOVER";

  public static class Binding extends StrictProtocolBinding<CircuitStopProtocol.StopController> {
    private final CircuitStopProtocol stop;

    public Binding(CircuitStopProtocol stop) {
      super("/libp2p/circuit/relay/0.2.0/stop", stop);
      this.stop = stop;
    }

    public void setTransport(RelayTransport transport) {
      stop.setTransport(transport);
    }
  }

  public interface StopController {
    CompletableFuture<Circuit.StopMessage> rpc(Circuit.StopMessage req);

    Stream getStream();

    default CompletableFuture<Circuit.StopMessage> connect(
        PeerId source, int durationSeconds, long maxBytes) {
      return rpc(
          Circuit.StopMessage.newBuilder()
              .setType(Circuit.StopMessage.Type.CONNECT)
              .setPeer(Circuit.Peer.newBuilder().setId(ByteString.copyFrom(source.getBytes())))
              .setLimit(Circuit.Limit.newBuilder().setData(maxBytes).setDuration(durationSeconds))
              .build());
    }
  }

  public static class Sender
      implements ProtocolMessageHandler<Circuit.StopMessage>, StopController {
    private final Stream stream;
    private final LinkedBlockingDeque<CompletableFuture<Circuit.StopMessage>> queue =
        new LinkedBlockingDeque<>();

    public Sender(Stream stream) {
      this.stream = stream;
    }

    @Override
    public void onMessage(@NotNull Stream stream, Circuit.StopMessage msg) {
      queue.poll().complete(msg);
    }

    public CompletableFuture<Circuit.StopMessage> rpc(Circuit.StopMessage req) {
      CompletableFuture<Circuit.StopMessage> res = new CompletableFuture<>();
      queue.add(res);
      stream.writeAndFlush(req);
      return res;
    }

    public Stream getStream() {
      return stream;
    }
  }

  public static class StopRemover extends ChannelInitializer {

    @Override
    protected void initChannel(@NotNull Channel ch) throws Exception {
      ch.pipeline().remove(ProtobufDecoder.class);
      ch.pipeline().remove(ProtobufEncoder.class);
      ch.pipeline().remove(ProtobufVarint32FrameDecoder.class);
      ch.pipeline().remove(ProtobufVarint32LengthFieldPrepender.class);
      ch.pipeline().remove(STOP_HANDLER_NAME);
      ch.pipeline().remove(STOP_REMOVER_NAME);
    }
  }

  public static class Receiver
      implements ProtocolMessageHandler<Circuit.StopMessage>, StopController {
    private final Stream stream;
    private final RelayTransport transport;

    public Receiver(Stream stream, RelayTransport transport) {
      this.stream = stream;
      this.transport = transport;
    }

    @Override
    public void onMessage(@NotNull Stream stream, Circuit.StopMessage msg) {
      if (msg.getType() == Circuit.StopMessage.Type.CONNECT) {
        PeerId remote = new PeerId(msg.getPeer().getId().toByteArray());
        int durationSeconds = msg.getLimit().getDuration();
        long limitBytes = msg.getLimit().getData();
        stream.writeAndFlush(
            Circuit.StopMessage.newBuilder()
                .setType(Circuit.StopMessage.Type.STATUS)
                .setStatus(Circuit.Status.OK)
                .build());
        // remove STOP handler from stream before upgrading
        stream.pushHandler(STOP_REMOVER_NAME, new StopRemover());

        // now upgrade connection with security and muxer protocol
        ConnectionHandler connHandler = null; // TODO
        RelayTransport.upgradeStream(
            stream, false, transport.upgrader, transport, remote, connHandler);
      }
    }

    public Stream getStream() {
      return stream;
    }

    public CompletableFuture<Circuit.StopMessage> rpc(Circuit.StopMessage msg) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Cannot send form a receiver!"));
    }
  }

  private static final int TRAFFIC_LIMIT = 2 * 1024;

  private RelayTransport transport;

  public CircuitStopProtocol() {
    super(Circuit.StopMessage.getDefaultInstance(), TRAFFIC_LIMIT, TRAFFIC_LIMIT);
  }

  public void setTransport(RelayTransport transport) {
    this.transport = transport;
  }

  @NotNull
  @Override
  protected CompletableFuture<StopController> onStartInitiator(@NotNull Stream stream) {
    Sender replyPropagator = new Sender(stream);
    stream.pushHandler(
        STOP_HANDLER_NAME, new ProtocolMessageHandlerAdapter<>(stream, replyPropagator));
    return CompletableFuture.completedFuture(replyPropagator);
  }

  @NotNull
  @Override
  protected CompletableFuture<StopController> onStartResponder(@NotNull Stream stream) {
    Receiver acceptor = new Receiver(stream, transport);
    stream.pushHandler(STOP_HANDLER_NAME, new ProtocolMessageHandlerAdapter<>(stream, acceptor));
    return CompletableFuture.completedFuture(acceptor);
  }
}
