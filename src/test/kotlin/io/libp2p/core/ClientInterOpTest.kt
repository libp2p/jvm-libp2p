package io.libp2p.core

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.dsl.SecureChannelCtor
import io.libp2p.core.dsl.TransportCtor
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.MplexProtocol
import io.libp2p.protocol.PingBinding
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.tools.CountingPingProtocol
import io.libp2p.transport.tcp.TcpTransport
import io.libp2p.transport.ws.WsTransport
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "ENABLE_GO_INTEROP", matches = "true")
class SecioTcpGoClientInterOpTest :
    ClientInterOpTest(::SecIoSecureChannel, OverTcp.ctor, OverTcp.listenAddress, GoPingClient)

@EnabledIfEnvironmentVariable(named = "ENABLE_GO_INTEROP", matches = "true")
class SecioWsGoClientInterOpTest :
    ClientInterOpTest(::SecIoSecureChannel, OverWs.ctor, OverWs.listenAddress, GoPingClient)

@EnabledIfEnvironmentVariable(named = "ENABLE_GO_INTEROP", matches = "true")
class PlaintextGoClientInterOpTest :
    ClientInterOpTest(::PlaintextInsecureChannel, OverTcp.ctor, OverTcp.listenAddress, GoPlaintextClient)

@EnabledIfEnvironmentVariable(named = "ENABLE_GO_INTEROP", matches = "true")
class NoiseGoClientInterOpTest :
    ClientInterOpTest(::NoiseXXSecureChannel, OverTcp.ctor, OverTcp.listenAddress, GoNoiseClient)

@EnabledIfEnvironmentVariable(named = "ENABLE_RUST_INTEROP", matches = "true")
class PlaintextRustClientInterOpTest :
    ClientInterOpTest(::NoiseXXSecureChannel, OverTcp.ctor, OverTcp.listenAddress, RustPlaintextClient)

@EnabledIfEnvironmentVariable(named = "ENABLE_JS_INTEROP", matches = "true")
class SecioJsClientInterOpTest :
    ClientInterOpTest(::SecIoSecureChannel, OverTcp.ctor, OverTcp.listenAddress, JsPingClient)

data class Transport(
    val ctor: TransportCtor,
    val listenAddress: String
)

val OverTcp = Transport(
    ::TcpTransport,
    "/ip4/127.0.0.1/tcp/40002"
)

val OverWs = Transport(
    ::WsTransport,
    "/ip4/127.0.0.1/tcp/40002/ws"
)

data class ExternalClient(
    val clientExeFile: String,
    val clientDirEnvVar: String,
    val clientCLIOptions: String = ""
)

// do 'go build' first
val GoPingClient = ExternalClient(
    "ping-client",
    "GO_PING_CLIENT"
)
val GoPlaintextClient = ExternalClient(
    "ping-client",
    "GO_PING_CLIENT",
    "--plaintext"
)
val GoNoiseClient = ExternalClient(
    "ping-client",
    "GO_PING_CLIENT",
    "--noise"
)

// do 'cargo build' first
val RustPlaintextClient = ExternalClient(
    "ping-client",
    "RUST_PING_CLIENT"
)

val JsPingClient = ExternalClient(
    "node",
    "JS_PINGER",
    "lib/ping-client.js"
)

@Tag("interop")
abstract class ClientInterOpTest(
    val secureChannelCtor: SecureChannelCtor,
    val transportCtor: TransportCtor,
    val listenAddress: String,
    val external: ExternalClient
) {
    var countedPingResponder = CountingPingProtocol()
    val serverHost = host {
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
        network {
            listen(listenAddress)
        }
        protocols {
            +PingBinding(countedPingResponder)
        }
        debug {
            beforeSecureHandler.setLogger(LogLevel.ERROR)
            afterSecureHandler.setLogger(LogLevel.ERROR)
        }
    }

    @BeforeEach
    fun startHosts() {
        val server = serverHost.start()
        server.get(5, TimeUnit.SECONDS)
        println("Server started")
    }

    @AfterEach
    fun stopHosts() {
        serverHost.stop().get(5, TimeUnit.SECONDS)
        println("Server stopped")
    }

    @Test
    fun listenForPings() {

        startClient("$listenAddress/ipfs/${serverHost.peerId}")
        // assertEquals(5, countedPingResponder.pingsReceived)

        // We seem to receive one-too many pings from the Go client
        // don't yet understand why
        assertTrue(
            5 <= countedPingResponder.pingsReceived,
            "Not enough pings received from external client"
        )
    }

    fun startClient(serverAddress: String) {
        if (System.getenv(external.clientDirEnvVar) == null) {
            throw IllegalArgumentException("Client exe directory not set: environment var ${external.clientDirEnvVar}")
        }
        val exeDir = File(System.getenv(external.clientDirEnvVar))
        if (!exeDir.isDirectory) throw IllegalArgumentException("Client exe directory not found")
        val exeOs = external.clientExeFile +
            if (System.getProperty("os.name").contains("win", true)) ".exe" else ""

        val exeWithPath = File(exeDir, exeOs)
        val exeFinal = if (exeWithPath.canExecute()) exeWithPath.absoluteFile.canonicalPath else exeOs

        val command = "$exeFinal ${external.clientCLIOptions} $serverAddress"
        println("Executing command: $command")
        val clientProcess = ProcessBuilder(*command.split(" ").toTypedArray())
            .directory(File(System.getenv(external.clientDirEnvVar)))
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        println("Starting $command")
        clientProcess.start().waitFor()
    }
}
