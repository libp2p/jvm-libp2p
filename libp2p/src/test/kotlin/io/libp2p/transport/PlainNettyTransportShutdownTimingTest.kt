package io.libp2p.transport

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Regression coverage for `PlainNettyTransport.close()` blocking for the default Netty
 * `shutdownGracefully` quiet period (2 seconds) on every call.
 *
 * `PlainNettyTransport.close()` chains the boss/worker `shutdownGracefully()` futures
 * via `CompletableFuture.allOf(...).thenApply { }`, so the caller's `close()` future
 * does not resolve until `shutdownGracefully()` does. With no arguments,
 * `shutdownGracefully()` falls back to Netty's defaults (`quietPeriod = 2 s`,
 * `timeout = 15 s`). The 2-second quiet period keeps the event loop alive in case more
 * work is submitted — but by the time `close()` is reached the transport is marked
 * closed, every channel has been closed, and no further work can be submitted to either
 * group. The 2-second wait is pure latency.
 *
 * That latency compounds for callers that drive many short-lived host lifecycles
 * back-to-back (e.g. test fixtures): 20 cycles cost ~40 s of pure quiet-period wait.
 * The fix is to pass `quietPeriod = 0` to `shutdownGracefully(...)`; the 5-second
 * `timeout` argument still bounds how long we wait for the loop to actually exit.
 */
@Tag("transport")
class PlainNettyTransportShutdownTimingTest {

    @Test
    fun `single close should resolve well below the default 2 second quiet period`() {
        val transport = TcpTransport(NullConnectionUpgrader())
        transport.initialize()
        transport.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), { _ -> }, null).get(5, TimeUnit.SECONDS)

        val elapsedMs = measureTimeMillis {
            transport.close().get(10, TimeUnit.SECONDS)
        }

        // 1-second budget: with quietPeriod=0 the close-future resolves in tens of
        // milliseconds even on slow CI; this still fails decisively if any nonzero
        // default quiet period is reintroduced.
        assertTrue(
            elapsedMs < 1_000,
            "PlainNettyTransport.close() returned in ${elapsedMs}ms — should be well " +
                "under the default 2-second shutdownGracefully quiet period. Check " +
                "whether shutdownGracefully was called with no arguments (defaults to " +
                "2 s quiet) instead of shutdownGracefully(0, ..., TimeUnit.SECONDS)."
        )
    }

    @Test
    fun `20 sequential listen-then-close cycles complete in under 5 seconds`() {
        // With the default 2 s quiet period this loop takes ~40 s; with the fix it
        // takes < 5 s.
        val totalElapsedMs = measureTimeMillis {
            for (i in 1..20) {
                val t = TcpTransport(NullConnectionUpgrader())
                t.initialize()
                t.listen(Multiaddr("/ip4/127.0.0.1/tcp/0"), { _ -> }, null)
                    .get(5, TimeUnit.SECONDS)
                t.close().get(10, TimeUnit.SECONDS)
            }
        }

        assertTrue(
            totalElapsedMs < 5_000,
            "20 sequential listen/close cycles took ${totalElapsedMs}ms — should be " +
                "under 5 s. Per-iteration latency: ~${totalElapsedMs / 20}ms."
        )
    }
}
