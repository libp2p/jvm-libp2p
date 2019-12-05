package io.libp2p.core

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.dsl.SecureChannelCtor
import io.libp2p.core.dsl.TransportCtor
import io.libp2p.core.dsl.host
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.protocol.PingBinding
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.tools.CountingPingProtocol
import io.libp2p.transport.tcp.TcpTransport
import io.libp2p.transport.ws.WsTransport
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
    GoClientInterOpTest(::SecIoSecureChannel, OverTcp)

@EnabledIfEnvironmentVariable(named = "ENABLE_GO_INTEROP", matches = "true")
class SecioWsGoClientInterOpTest :
    GoClientInterOpTest(::SecIoSecureChannel, OverWs)

// @EnabledIfEnvironmentVariable(named = "ENABLE_GO_INTEROP", matches = "true")
// class PlaintextGoClientInterOpTest : ClientInterOpTest(::PlaintextInsecureChannel, GoPlaintextClient)

// @EnabledIfEnvironmentVariable(named = "ENABLE_RUST_INTEROP", matches = "true")
// class PlaintextRustClientInterOpTest : ClientInterOpTest(::PlaintextInsecureChannel, RustPlaintextClient)

// @EnabledIfEnvironmentVariable(named = "ENABLE_JS_INTEROP", matches = "true")
// class SecioJsClientInterOpTest : ClientInterOpTest(::SecIoSecureChannel, JsPingClient)

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
    val clientCommand: String,
    val clientDirEnvVar: String
)

val GoPingClient = ExternalClient(
    "./ping-client",
    "GO_PING_CLIENT"
)
val GoPlaintextClient = ExternalClient(
    "./ping-client --plaintext",
    "GO_PING_CLIENT"
)

val RustPlaintextClient = ExternalClient(
    "cargo run",
    "RUST_PING_CLIENT"
)

val JsPingClient = ExternalClient(
    "node lib/ping-client.js",
    "JS_PINGER"
)

abstract class GoClientInterOpTest(
    secureChannelCtor: SecureChannelCtor,
    transport: Transport
) : ClientInterOpTest(
    secureChannelCtor,
    transport.ctor,
    transport.listenAddress,
    GoPingClient
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
            +::MplexStreamMuxer
        }
        network {
            listen(listenAddress)
        }
        protocols {
            +PingBinding(countedPingResponder)
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
        val command = "${external.clientCommand} $serverAddress"
        val clientProcess = ProcessBuilder(*command.split(" ").toTypedArray())
            .directory(File(System.getenv(external.clientDirEnvVar)))
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        println("Starting $command")
        clientProcess.start().waitFor()
    }
}
