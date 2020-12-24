package io.libp2p.core

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.dsl.SecureChannelCtor
import io.libp2p.core.dsl.TransportCtor
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.MplexProtocol
import io.libp2p.etc.types.getX
import io.libp2p.protocol.Identify
import io.libp2p.protocol.IdentifyController
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.tools.DoNothing
import io.libp2p.tools.DoNothingController
import io.libp2p.transport.tcp.TcpTransport
import io.libp2p.transport.ws.WsTransport
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "ENABLE_GO_INTEROP", matches = "true")
class SecioTcpGoServerInterOpTest : ServerInterOpTest(::SecIoSecureChannel, ::TcpTransport, GoServer)

@EnabledIfEnvironmentVariable(named = "ENABLE_GO_INTEROP", matches = "true")
class NoiseTcpGoServerInterOpTest : ServerInterOpTest(::NoiseXXSecureChannel, ::TcpTransport, GoServer)

@EnabledIfEnvironmentVariable(named = "ENABLE_GO_INTEROP", matches = "true")
class SecioWsGoServerInterOpTest : ServerInterOpTest(::SecIoSecureChannel, ::WsTransport, GoServer)

@EnabledIfEnvironmentVariable(named = "ENABLE_RUST_INTEROP", matches = "true")
class PlaintextTcpRustServerInterOpTest : ServerInterOpTest(::NoiseXXSecureChannel, ::TcpTransport, RustServer)

// @EnabledIfEnvironmentVariable(named = "ENABLE_RUST_INTEROP", matches = "true")
// class PlaintextTcpRustServerInterOpTest : ServerInterOpTest(::PlaintextInsecureChannel, ::TcpTransport, RustServer)

// @EnabledIfEnvironmentVariable(named = "ENABLE_GO_INTEROP", matches = "true")
// class PlaintextGoServerInterOpTest : ServerInterOpTest(::PlaintextInsecureChannel, GoPlaintextServer)

// @EnabledIfEnvironmentVariable(named = "ENABLE_JS_INTEROP", matches = "true")
// class SecioJsServerInterOpTest : ServerInterOpTest(::SecIoSecureChannel, JsPingServer)

data class ExternalServer(
    val serverExe: String,
    val serverDirEnvVar: String,
    val pingCount: Int = 5,
    val clientCLIOptions: String = ""
)

val GoServer = ExternalServer(
    "ping-server",
    "GO_PING_SERVER"
)
val RustServer = ExternalServer(
    "cargo",
    "RUST_PING_SERVER",
    1, // Rust ping protocol only responds to one ping per session.
    "run --quiet --"
)
val JsPingServer = ExternalServer(
    "node",
    "JS_PINGER",
    5,
    "lib/ping-server.js"
)

@Tag("interop")
abstract class ServerInterOpTest(
    val secureChannelCtor: SecureChannelCtor,
    val transportCtor: TransportCtor,
    val external: ExternalServer
) {
    val clientHost = host {
        identity {
            random(KEY_TYPE.RSA)
        }
        transports {
            add(transportCtor)
        }
        secureChannels {
            add(secureChannelCtor)
        }
        muxers {
            + MplexProtocol
        }
        protocols {
            +Ping()
            +Identify()
            +DoNothing()
        }
        debug {
            beforeSecureHandler.setLogger(LogLevel.ERROR)
            afterSecureHandler.setLogger(LogLevel.ERROR)
            muxFramesHandler.setLogger(LogLevel.ERROR)
        }
    }

    fun serverCommandLine(): Array<String> {
        if (System.getenv(external.serverDirEnvVar) == null) {
            throw IllegalArgumentException("Client exe directory not set: environment var ${external.serverDirEnvVar}")
        }
        val exeDir = File(System.getenv(external.serverDirEnvVar))
        if (!exeDir.isDirectory) throw IllegalArgumentException("Client exe directory not found")
        val exeOs = external.serverExe +
            if (System.getProperty("os.name").contains("win", true)) ".exe" else ""

        val exeWithPath = File(exeDir, exeOs)
        val exeFinal = if (exeWithPath.canExecute()) exeWithPath.absoluteFile.canonicalPath else exeOs

        val command = "$exeFinal ${external.clientCLIOptions}"
        println("Executing command: $command")

        val args = mutableListOf<String>()
        args.addAll(command.split(" "))

        if (transportCtor == ::WsTransport)
            args.add("--websocket")

        if (secureChannelCtor == ::PlaintextInsecureChannel)
            args.add("--plaintext")

        if (secureChannelCtor == ::NoiseXXSecureChannel)
            args.add("--noise")

        println("Running command: " + args.joinToString(" "))
        return args.toTypedArray()
    }
    val serverHost = ProcessBuilder(*serverCommandLine())
        .directory(File(System.getenv(external.serverDirEnvVar)))
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)

    lateinit var server: Process
    val serverMultiAddress = Multiaddr("/ip4/127.0.0.1/tcp/44444")
    val serverPeerId = PeerId.fromBase58("12D3KooWDRpGddqQ1UVH7s2PVdqVwnwfGic9QhHxY7HfMK1dZBCh")

    @BeforeEach
    fun startServer() {
        server = serverHost.start()
    }

    @AfterEach
    fun stopServer() {
        server.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        println("Server stopped")
    }

    @BeforeEach
    fun startClient() {
        val client = clientHost.start()
        client.get(5, TimeUnit.SECONDS)
        println("Client started")
    }

    @AfterEach
    fun stopHosts() {
        clientHost.stop().get(5, TimeUnit.SECONDS)
        println("Client Host stopped")
    }

    @Test
    fun unsupportedServerProtocol() {
        // remote party doesn't support the protocol
        val unsupportedProtocol = clientHost.newStream<DoNothingController>(
            listOf("/ipfs/do-nothing/1.0.0"),
            serverPeerId,
            serverMultiAddress
        )
        // stream should be created
        unsupportedProtocol.stream.get()
        println("Stream created")
        // ... though protocol controller should fail
        assertThrows(NoSuchProtocolException::class.java) { unsupportedProtocol.controller.getX() }
    }

    @Test
    fun pingOverSecureConnection() {
        val ping = clientHost.newStream<PingController>(
            listOf("/ipfs/ping/1.0.0"),
            serverPeerId,
            serverMultiAddress
        )
        val pingStream = ping.stream.get(5, TimeUnit.SECONDS)
        println("Ping stream created")
        val pingCtr = ping.controller.get(10, TimeUnit.SECONDS)
        println("Ping controller created")

        for (i in 1..external.pingCount) {
            val latency = pingCtr.ping().get(1, TimeUnit.SECONDS)
            println("Ping is $latency")
        }
        pingStream.close().get(5, TimeUnit.SECONDS)
        println("Ping stream closed")

        // stream is closed, the call should fail correctly
        assertThrows(ConnectionClosedException::class.java) {
            pingCtr.ping().getX(5.0)
        }
    }

    @Test
    fun identifyOverSecureConnection() {
        val identify = clientHost.newStream<IdentifyController>(
            listOf("/ipfs/id/1.0.0"),
            serverPeerId,
            serverMultiAddress
        )
        val identifyStream = identify.stream.get(5, TimeUnit.MINUTES)
        println("Identify stream created")
        val identifyController = identify.controller.get(5, TimeUnit.MINUTES)
        println("Identify controller created")

        val remoteIdentity = identifyController.id().get(5, TimeUnit.SECONDS)
        println(remoteIdentity)

        identifyStream.close().get(5, TimeUnit.SECONDS)
        println("Identify stream closed")

        assertTrue(remoteIdentity.protocolsList.contains("/ipfs/id/1.0.0"))
        assertTrue(remoteIdentity.protocolsList.contains("/ipfs/ping/1.0.0"))

        assertEquals(
            identifyStream.connection.localAddress(),
            Multiaddr(remoteIdentity.observedAddr.toByteArray())
        )

        val remoteAddress = Multiaddr(remoteIdentity.listenAddrsList[0].toByteArray())
        assertEquals(serverMultiAddress, remoteAddress)
        assertEquals(identifyStream.connection.remoteAddress(), remoteAddress)
    }
}
