package io.libp2p.core

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.dsl.SecureChannelCtor
import io.libp2p.core.dsl.host
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.protocol.PingBinding
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.tools.CountingPingProtocol
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "ENABLE_GO_INTEROP", matches = "true")
class SecioGoClientInterOpTest : ClientInterOpTest(::SecIoSecureChannel, GoPingClient)

@EnabledIfEnvironmentVariable(named = "ENABLE_GO_INTEROP", matches = "true")
class PlaintextGoClientInterOpTest : ClientInterOpTest(::PlaintextInsecureChannel, GoPlaintextClient)

// @EnabledIfEnvironmentVariable(named = "ENABLE_RUST_INTEROP", matches = "true")
// class PlaintextRustClientInterOpTest : ClientInterOpTest(::PlaintextInsecureChannel, RustPlaintextClient)

// @EnabledIfEnvironmentVariable(named = "ENABLE_JS_INTEROP", matches = "true")
// class SecioJsClientInterOpTest : ClientInterOpTest(::SecIoSecureChannel, JsPingClient)

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

@Tag("interop")
abstract class ClientInterOpTest(
    val secureChannelCtor: SecureChannelCtor,
    val external: ExternalClient
) {
    var countedPingResponder = CountingPingProtocol()
    val serverHost = host {
        identity {
            random(KEY_TYPE.RSA)
        }
        transports {
            +::TcpTransport
        }
        secureChannels {
            add(secureChannelCtor)
        }
        muxers {
            +::MplexStreamMuxer
        }
        network {
            listen("/ip4/0.0.0.0/tcp/40002")
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
        startClient("/ip4/127.0.0.1/tcp/40002/ipfs/${serverHost.peerId}")
        assertEquals(5, countedPingResponder.pingsReceived)
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
