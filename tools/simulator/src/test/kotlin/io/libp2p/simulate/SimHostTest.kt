package io.libp2p.simulate

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.crypto.unmarshalPrivateKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.getX
import io.libp2p.protocol.Identify
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.simulate.connection.LoopbackNetwork
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class SimHostTest {

    @Test
    fun pingOverSecureConnection() {
        val timeController = TimeControllerImpl()

        val net = LoopbackNetwork().also {
            it.simExecutor = ControlledExecutorServiceImpl(timeController)
        }

        val clientHost = net.newPeer {
            identity {
                factory = { unmarshalPrivateKey("0803129601308193020100301306072a8648ce3d020106082a8648ce3d0301070479307702010104200920e70bed663fbfe500cc661505b0a1b1ec55ff78e466cf4114a5d557a18e60a00a06082a8648ce3d030107a1440342000411697ca3697df00c25991ec76626d7cea84ee4e4ae91d6e5da3791917682ddccdd60cc9918a18cb2383fb2dc4a6782f585f13226616308f651ecfb55d90a3e20".fromHex()) }
            }
            secureChannels {
                +::SecIoSecureChannel
            }
            muxers {
                + StreamMuxerProtocol.Mplex
            }
            protocols {
                +Ping()
                +Identify()
            }
            debug {
                beforeSecureHandler.addLogger(LogLevel.ERROR)
                afterSecureHandler.addLogger(LogLevel.ERROR)
                muxFramesHandler.addLogger(LogLevel.ERROR)
            }
        }

        val serverHost = net.newPeer {
            identity {
                factory = { unmarshalPrivateKey("0803129601308193020100301306072a8648ce3d020106082a8648ce3d0301070479307702010104208d8935b8805503f2503495ab01ae6c54bc13339ec75a78449230c69b8d12bf55a00a06082a8648ce3d030107a1440342000480ec01d6c3b23fc97cfa200aa46a0f7c636e69e4522471e6cf6721c3d7eb07d28e7d46d16741b901e8a362b4bdbe3e2f99f4bf274a1307e9cebf50ff97e39176".fromHex()) }
            }
            secureChannels {
                +::SecIoSecureChannel
            }
            muxers {
                + StreamMuxerProtocol.Mplex
            }
            network {
                listen("/ip4/0.0.0.0/tcp/40002")
            }
            protocols {
                +Ping()
            }
            debug {
                beforeSecureHandler.addLogger(LogLevel.ERROR)
                afterSecureHandler.addLogger(LogLevel.ERROR)
                muxFramesHandler.addLogger(LogLevel.ERROR)
            }
        }

        clientHost.start().get(1, TimeUnit.SECONDS)
        serverHost.start().get(1, TimeUnit.SECONDS)

        val ping = clientHost.host.newStream<PingController>(
            listOf("/ipfs/ping/1.0.0"),
            serverHost.host.peerId,
            Multiaddr("/ip4/${serverHost.ip}/tcp/40002")
        )
        val pingStream = ping.stream.get(5, TimeUnit.SECONDS)
        println("Ping stream created")
        val pingCtr = ping.controller.get(10, TimeUnit.SECONDS)
        println("Ping controller created")

        for (i in 1..10) {
            val latency = pingCtr.ping().get(1, TimeUnit.SECONDS)
            println("Ping $i is ${latency}ms")
        }
        pingStream.close().get(5, TimeUnit.SECONDS)
        println("Ping stream closed")

        // stream is closed, the call should fail correctly
        assertThrows(ConnectionClosedException::class.java) {
            pingCtr.ping().getX(5.0)
        }
    }
}
