package io.libp2p.transport

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Regression coverage for a port-release race between concurrent calls to
 * [PlainNettyTransport.listen] and [PlainNettyTransport.close].
 *
 * `listen()` writes to the [PlainNettyTransport.listeners] map under
 * `synchronized(this@PlainNettyTransport)`. But `close()` reads the same
 * map **without** acquiring the transport monitor:
 *
 *     override fun close(): CompletableFuture<Unit> {
 *         closed = true
 *
 *         val unbindsCompleted = listeners                       // <-- unsynchronised read
 *             .map { (_, ch) -> ch }
 *             .map { it.close().toVoidCompletableFuture() }
 *         ...
 *     }
 *
 * If `close()` reads `listeners` while another thread is between
 * `listener.bind(addr)` (which queued the bind on the boss event loop, so the
 * port WILL eventually be bound) and the synchronized add to the map, `close()`
 * observes an empty map. It skips closing the listener channel and proceeds
 * straight to `shutdownGracefully`. Netty's event loop still runs the queued
 * bind task before the loop terminates — the port becomes bound — but the
 * channel is now orphaned (never explicitly closed). The OS file descriptor
 * stays open until JVM exit, so any subsequent bind to the same port from this
 * JVM fails with `BindException: Address already in use`.
 *
 * What this test pins:
 *   - Many iterations of `listen` racing with `close` on the same port leave
 *     the port verifiably free after each iteration. With the unfixed close()
 *     a single leaked listener cascades into "Address already in use" on every
 *     subsequent ServerSocket bind, surfacing as repeated BindException across
 *     the rest of the test.
 */
@Tag("transport")
class PlainNettyTransportConcurrentListenCloseTest {

    @Test
    fun `concurrent listen and close on the same port must not leak the listener channel`() {
        // Pick a specific ephemeral port up front and reuse it for every iteration
        // so a single leak deterministically cascades — that's the symptom that
        // makes the race observable from a test (rather than a no-op when each
        // iteration uses a different port).
        val port = ServerSocket(0).use { it.localPort }
        val addr = Multiaddr("/ip4/127.0.0.1/tcp/$port")

        val iterations = 200
        var firstFailure = -1
        var firstFailureMessage = ""
        var failureCount = 0

        for (i in 1..iterations) {
            val transport = TcpTransport(NullConnectionUpgrader())
            transport.initialize()

            // Start listen() on a separate thread so close() can race it from
            // this thread. listen() schedules the bind on the boss event loop
            // and returns; the synchronized map update happens BEFORE the
            // returned future resolves, but close() reads the map without
            // acquiring the same monitor.
            val listenFuture = transport.listen(addr, { _ -> }, null)

            // Race the close as tightly as possible against the in-flight listen.
            // We don't wait for listenFuture to complete: that's the whole point —
            // close() must work correctly even when the bind is still on the wire.
            val closeFuture = transport.close()

            // Wait for both to finish so the next iteration starts from a clean
            // state. We tolerate listen failing (the transport was closed) but
            // close() must always complete.
            try {
                listenFuture.get(10, TimeUnit.SECONDS)
            } catch (_: Throwable) {
                // listen() can legitimately fail if close() ran first.
            }
            closeFuture.get(10, TimeUnit.SECONDS)

            // After close() returns the port MUST be bindable. If close()
            // missed the listener (race window), the port stays held.
            try {
                ServerSocket(port).close()
            } catch (e: BindException) {
                failureCount++
                if (firstFailure < 0) {
                    firstFailure = i
                    firstFailureMessage = "iter=$i: port $port not released after " +
                        "transport.close() returned — listener channel was not " +
                        "closed because close() read listeners without holding " +
                        "the transport monitor. BindException: ${e.message}"
                }
            }
        }

        assertEquals(
            0,
            failureCount,
            "Port $port leaked after $failureCount/$iterations listen-close races. " +
                "First failure: $firstFailureMessage"
        )
    }

    /**
     * Tighter form of the same race using an explicit thread to start `close()`
     * concurrently with `listen()`. The first variant relies on `listen()`'s
     * own async bind completion racing close(); this one launches both in
     * parallel from separate threads to maximize the chance of close() reading
     * the listeners map between bind() and the synchronized add.
     */
    @Test
    fun `parallel listen and close from separate threads must not leak the listener channel`() {
        val port = ServerSocket(0).use { it.localPort }
        val addr = Multiaddr("/ip4/127.0.0.1/tcp/$port")

        val iterations = 200
        val failureCount = AtomicInteger(0)
        var firstFailureIter = -1
        var firstFailureMessage = ""

        for (i in 1..iterations) {
            val transport = TcpTransport(NullConnectionUpgrader())
            transport.initialize()

            val barrier = CountDownLatch(1)
            val listenStarted = AtomicBoolean(false)

            val listenThread = thread(name = "listen-$i", isDaemon = true) {
                barrier.await()
                try {
                    listenStarted.set(true)
                    transport.listen(addr, { _ -> }, null).get(5, TimeUnit.SECONDS)
                } catch (_: Throwable) {
                    // listen() can legitimately fail if close() preempted it.
                }
            }
            val closeThread = thread(name = "close-$i", isDaemon = true) {
                barrier.await()
                try {
                    transport.close().get(10, TimeUnit.SECONDS)
                } catch (_: Throwable) {
                    // Best effort: close() should succeed but we don't fail the
                    // test on a transport-level exception — we fail on the port
                    // leak observation below.
                }
            }

            // Release both threads simultaneously.
            barrier.countDown()
            listenThread.join(15_000)
            closeThread.join(15_000)

            // Both transports are torn down. The port should be bindable.
            try {
                ServerSocket(port).close()
            } catch (e: BindException) {
                val count = failureCount.incrementAndGet()
                if (count == 1) {
                    firstFailureIter = i
                    firstFailureMessage = "iter=$i: port $port not released. " +
                        "BindException: ${e.message}"
                }
            }
        }

        assertTrue(
            failureCount.get() == 0,
            "Port $port leaked after ${failureCount.get()}/$iterations parallel " +
                "listen-close races (first at iter $firstFailureIter). " +
                "$firstFailureMessage"
        )
    }
}
