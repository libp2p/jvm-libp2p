package io.libp2p.transport.quic;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.quic.QuicException;
import io.netty.handler.codec.quic.QuicTransportError;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class QuicConnectionExceptionHandlerTest {

  private static class CapturingHandler extends ChannelInboundHandlerAdapter {
    private final List<Throwable> caught = new ArrayList<>();

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      caught.add(cause);
    }
  }

  @Test
  void quicExceptionIsConsumedAndClosesChannel() {
    CapturingHandler capturing = new CapturingHandler();
    EmbeddedChannel channel = new EmbeddedChannel(new QuicConnectionExceptionHandler(), capturing);
    QuicException exception =
        new QuicException("invalid QUIC state", QuicTransportError.PROTOCOL_VIOLATION);

    channel.pipeline().fireExceptionCaught(exception);
    channel.runPendingTasks();

    Assertions.assertFalse(channel.isOpen());
    Assertions.assertTrue(capturing.caught.isEmpty());
    channel.finishAndReleaseAll();
  }

  @Test
  void unrelatedExceptionIsPropagatedWithoutClosingChannel() {
    CapturingHandler capturing = new CapturingHandler();
    EmbeddedChannel channel = new EmbeddedChannel(new QuicConnectionExceptionHandler(), capturing);
    RuntimeException exception = new RuntimeException("boom");

    channel.pipeline().fireExceptionCaught(exception);

    Assertions.assertTrue(channel.isOpen());
    Assertions.assertEquals(List.of(exception), capturing.caught);
    channel.finishAndReleaseAll();
  }
}
