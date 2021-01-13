package io.libp2p.tools

import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.etc.types.thenApplyAll
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.protocol.Identify
import io.libp2p.protocol.Ping
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.handler.logging.LogLevel
import java.util.concurrent.TimeUnit

class HostFactory {

    var keyType = KEY_TYPE.ECDSA
    var tcpPort = 5000
    var transportCtor = ::TcpTransport
    var secureCtor = ::NoiseXXSecureChannel
    var mplexCtor = ::MplexStreamMuxer
    var muxLogLevel: LogLevel? = LogLevel.DEBUG

    val createdHosts = mutableListOf<TestHost>()

    fun createHost(): TestHost {
        val keys = generateKeyPair(keyType)
        val port = tcpPort++
        val address = Multiaddr.fromString("/ip4/127.0.0.1/tcp/$port")

        val host = host {
            identity {
                factory = { keys.first }
            }
            transports {
                add(transportCtor)
            }
            secureChannels {
                add(secureCtor)
            }
            muxers {
                add(mplexCtor)
            }
            network {
                listen(address.toString())
            }
            protocols {
                +Ping()
                +Identify()
                +Echo()
            }
            debug {
                muxLogLevel?.also {
                    muxFramesHandler.setLogger(it, "host-$port") // don't log all that spam during DoS test
                }
            }
        }
        host.start().get(5, TimeUnit.SECONDS)

        return TestHost(host, keys.first, keys.second, PeerId.fromPubKey(keys.second), address)
            .also { createdHosts += it }
    }

    fun shutdown() {
        createdHosts.map { it.host.stop() }.thenApplyAll { }.get(createdHosts.size * 1L + 5, TimeUnit.SECONDS)
    }
}

data class TestHost(
    val host: Host,
    val privKey: PrivKey,
    val pubKey: PubKey,
    val peerId: PeerId,
    val listenAddress: Multiaddr,
    val listenPort: Int? = listenAddress?.getStringComponent(Protocol.TCP)?.toInt()
)
