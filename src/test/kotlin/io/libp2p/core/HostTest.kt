package io.libp2p.core

import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.mux.mplex.MplexStreamMuxer
import io.libp2p.core.protocol.Ping
import io.libp2p.core.security.secio.SecIoSecureChannel
import io.libp2p.core.transport.tcp.TcpTransport
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
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
                add(::SecIoSecureChannel)
            }
            muxers {
                +::MplexStreamMuxer
            }
            protocols {
                +Ping()
            }
            debug {
                beforeSecureHandler.setLogger(LogLevel.ERROR)
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
                add(::SecIoSecureChannel)
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

        val ping =
            host1.network.connect(host2.peerId, Multiaddr("/ip4/127.0.0.1/tcp/40002"))
                .thenApply { it.muxerSession.createStream(Multistream.create(Ping())) }
                .get(5, TimeUnit.SECONDS)
        val pingStream = ping.stream.get(5, TimeUnit.SECONDS)
        println("Ping stream created")
        val pingCtr = ping.controler.get(5, TimeUnit.SECONDS)
        println("Ping controller created")

        for (i in 1..10) {
            val latency = pingCtr.ping().get(1, TimeUnit.SECONDS)
            println("Ping is $latency")
        }
        pingStream.ch.close().await(5, TimeUnit.SECONDS)
        println("Ping stream closed")

        Assertions.assertThrows(ExecutionException::class.java) {
            pingCtr.ping().get(5, TimeUnit.SECONDS)
        }

        host1.stop().get(5, TimeUnit.SECONDS)
        println("Host #1 stopped")
        host2.stop().get(5, TimeUnit.SECONDS)
        println("Host #2 stopped")

        // // What is the status of this peer? Are we connected to it? Do we know them (i.e. have addresses for them?)
        // host.peer(id).status()
        //
        // val connection = host.peer(id).connect()
        // // Disconnect this peer.
        // host.peer(id).disconnect()
        // // Get a connection, if any.
        // host.peer(id).connection()
        //
        // // Get this peer's addresses.
        // val addrs = host.peer(id).addrs()
        //
        // // Create a stream.
        // host.peer(id).streams().create("/eth2/1.0.0")
    }
}