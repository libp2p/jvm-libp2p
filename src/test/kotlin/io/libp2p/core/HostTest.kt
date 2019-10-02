package io.libp2p.core

import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.etc.types.getX
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.protocol.Identify
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class HostTest {

    @Test
    fun testHost() {

        // Let's create a host! This is a fluent builder.
        val host1 = host {
            identity {
                random()
            }
            transports {
                +::TcpTransport
            }
            secureChannels {
//                add(::SecIoSecureChannel)
                add(::NoiseXXSecureChannel)
            }
            muxers {
                +::MplexStreamMuxer
            }
            protocols {
                +Ping()
                +Identify()
            }
            debug {
                afterSecureHandler.setLogger(LogLevel.ERROR)
                muxFramesHandler.setLogger(LogLevel.ERROR)
            }
        }

        val host2 = host {
            identity {
                random()
            }
            transports {
                +::TcpTransport
            }
            secureChannels {
//                add(::SecIoSecureChannel)
                add(::NoiseXXSecureChannel)
            }
            muxers {
                +::MplexStreamMuxer
            }
            network {
                listen("/ip4/0.0.0.0/tcp/40002")
            }
            protocols {
                +Ping()
            }
        }

        val start1 = host1.start()
        val start2 = host2.start()
        start1.get(5, TimeUnit.SECONDS)
        println("Host #1 started")
        start2.get(5, TimeUnit.SECONDS)
        println("Host #2 started")

        // invalid protocol name
        val streamPromise1 = host1.newStream<PingController>("/__no_such_protocol/1.0.0", host2.peerId, Multiaddr("/ip4/127.0.0.1/tcp/40002"))
        Assertions.assertThrows(NoSuchProtocolException::class.java) { streamPromise1.stream.getX(5.0) }
        Assertions.assertThrows(NoSuchProtocolException::class.java) { streamPromise1.controler.getX(5.0) }

        // remote party doesn't support the protocol
        val streamPromise2 = host1.newStream<PingController>("/ipfs/id/1.0.0", host2.peerId, Multiaddr("/ip4/127.0.0.1/tcp/40002"))
        // stream should be created
        streamPromise2.stream.get()
        println("Stream created")
        // ... though protocol controller should fail
        Assertions.assertThrows(NoSuchProtocolException::class.java) { streamPromise2.controler.getX() }

        val ping = host1.newStream<PingController>("/ipfs/ping/1.0.0", host2.peerId, Multiaddr("/ip4/127.0.0.1/tcp/40002"))
        val pingStream = ping.stream.get(5, TimeUnit.SECONDS)
        println("Ping stream created")
        val pingCtr = ping.controler.get(5, TimeUnit.SECONDS)
        println("Ping controller created")

        for (i in 1..10) {
            val latency = pingCtr.ping().get(1, TimeUnit.SECONDS)
            println("Ping is $latency")
        }
        pingStream.nettyChannel.close().await(5, TimeUnit.SECONDS)
        println("Ping stream closed")

        // stream is closed, the call should fail correctly
        Assertions.assertThrows(ConnectionClosedException::class.java) {
            pingCtr.ping().getX(5.0)
        }

        host1.stop().get(5, TimeUnit.SECONDS)
        println("Host #1 stopped")
        host2.stop().get(5, TimeUnit.SECONDS)
        println("Host #2 stopped")
    }
}