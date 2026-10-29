package io.libp2p.transport.quic;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.quic.QuicException;
import io.netty.handler.codec.quic.QuicTransportError;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class QuicConnectionExceptionHandlerTest {

  private static class CapturingHandler extends ChannelInboundHandlerAdapter {
    private final List<Throwable> caught = new ArrayList<>();

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      caught.add(cause);
    }
  }

  static Stream<Throwable> quicExceptions() {
    return Stream.of(
        new QuicException("invalid QUIC state", QuicTransportError.PROTOCOL_VIOLATION),
        // Netty reports QUICHE_ERR_CRYPTO_FAIL as an SSLException wrapping the QuicException.
        new SSLException(new QuicException("QUICHE_ERR_CRYPTO_FAIL", QuicTransportError.NO_ERROR)));
  }

  static Stream<Throwable> unrelatedExceptions() {
    return Stream.of(new SSLException("not from quiche"), new RuntimeException("boom"));
  }

  @ParameterizedTest
  @MethodSource("quicExceptions")
  void quicExceptionIsConsumedAndClosesChannel(Throwable exception) {
    CapturingHandler capturing = new CapturingHandler();
    EmbeddedChannel channel = new EmbeddedChannel(new QuicConnectionExceptionHandler(), capturing);

    channel.pipeline().fireExceptionCaught(exception);
    channel.runPendingTasks();

    Assertions.assertFalse(channel.isOpen());
    Assertions.assertTrue(capturing.caught.isEmpty());
    channel.finishAndReleaseAll();
  }

  @ParameterizedTest
  @MethodSource("unrelatedExceptions")
  void unrelatedExceptionIsPropagatedWithoutClosingChannel(Throwable exception) {
    CapturingHandler capturing = new CapturingHandler();
    EmbeddedChannel channel = new EmbeddedChannel(new QuicConnectionExceptionHandler(), capturing);

    channel.pipeline().fireExceptionCaught(exception);

    Assertions.assertTrue(channel.isOpen());
    Assertions.assertEquals(List.of(exception), capturing.caught);
    channel.finishAndReleaseAll();
  }
}
