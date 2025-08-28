package io.libp2p.interop

import identify.pb.IdentifyOuterClass
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.PeerId.Companion.fromPubKey
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.dsl.Builder
import io.libp2p.core.dsl.hostJ
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.core.mux.StreamMuxerProtocol.Companion.Mplex
import io.libp2p.core.mux.StreamMuxerProtocol.Companion.getYamux
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.etc.types.toProtobuf
import io.libp2p.protocol.Identify
import io.libp2p.protocol.Ping
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.security.tls.TlsSecureChannel.Companion.ECDSA
import io.libp2p.transport.tcp.TcpTransport
import redis.clients.jedis.Jedis
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import kotlin.random.Random
import kotlin.system.exitProcess

private const val REDIS_KEY_LISTENER_ADDRESS = "listenerAddr"

class InteropTestAgent(val params: InteropTestParams) {

    private val advertisedAddress: Multiaddr
    private val node: Host

    init {
        val port = 10000 + Random.nextInt(50000)
        val isTcp = "tcp" == params.transport
        val ip = params.ip
        val protocol = if (isTcp) "tcp" else "udp"
        val maybeQuicSuffix = (if (isTcp) "" else "/quic-v1")
        val address =
            Multiaddr.fromString("/ip4/$ip/$protocol/${port}$maybeQuicSuffix")

        val privateKey = generateEd25519KeyPair().first
        val peerID = fromPubKey(privateKey.publicKey())
        advertisedAddress = address.withP2P(peerID)

        val listenAddresses = ArrayList<String>()
        listenAddresses.add(address.toString())
        val protocols = createProtocols(privateKey, listenAddresses)
        node = createHost(privateKey, protocols, listenAddresses)
    }

    fun run(): CompletableFuture<Void> {
        return node.start()
            .thenCompose { startJedisConnection() }
            .thenCompose { jedis ->
                if (params.isDialer) {
                    startDialer(jedis, node)
                } else {
                    startListener(jedis, advertisedAddress)
                }
            }.whenComplete { _, _ -> node.stop() }
    }

    private fun createHost(
        privateKey: PrivKey,
        protocols: ArrayList<ProtocolBinding<Any>>,
        listenAddresses: ArrayList<String>
    ): Host = hostJ(Builder.Defaults.None, fn = {
        it.identity.factory = { privateKey }
        if (params.transport == "quic-v1") {
            // TODO add quic support
        } else {
            it.transports.add(::TcpTransport)
        }

        if ("noise" == params.security) {
            it.secureChannels.add(::NoiseXXSecureChannel)
        } else if ("tls" == params.security) {
            it.secureChannels.add(::ECDSA)
        }

        val muxers = ArrayList<StreamMuxerProtocol>()
        if ("mplex" == params.muxer) {
            muxers.add(Mplex)
        } else if ("yamux" == params.muxer) {
            muxers.add(getYamux())
        }
        it.muxers.addAll(muxers)

        for (protocol in protocols) {
            it.protocols.add(protocol)
        }

        for (listenAddr in listenAddresses) {
            it.network.listen(listenAddr)
        }

        it.connectionHandlers.add {
            ConnectionHandler { conn: Connection ->
                printDiagnosticsLog(
                    (
                        conn.localAddress()
                            .toString() + " received connection from " +
                            conn.remoteAddress() +
                            " on transport " +
                            conn.transport()
                        )
                )
            }
        }
    })

    private fun startJedisConnection(): CompletableFuture<Jedis> {
        return CompletableFuture.supplyAsync {
            val jedis = Jedis("http://${params.redisAddress}")
            var isReady = false
            while (!isReady) {
                if ("PONG" == jedis.ping()) {
                    isReady = true
                } else {
                    printDiagnosticsLog("waiting for redis to start...")
                    Thread.sleep(1000)
                }
            }
            printDiagnosticsLog("Connection established to Redis ($jedis)")
            jedis
        }
    }

    /*
      Start dialer and try to connect with a listener
     */
    private fun startDialer(jedis: Jedis, node: Host): CompletableFuture<Void> {
        return CompletableFuture.supplyAsync {
            printDiagnosticsLog("Starting dialer")

            val listenerAddresses =
                jedis.blpop(params.testTimeoutInSeconds, REDIS_KEY_LISTENER_ADDRESS)
            if (listenerAddresses == null || listenerAddresses.isEmpty()) {
                throw IllegalStateException("listenerAddr not set")
            }

            val listenerAddr =
                Multiaddr.fromString(listenerAddresses.first { s -> s.startsWith("/") })

            printDiagnosticsLog("Sending ping messages to $listenerAddr")

            val handshakeStart = System.currentTimeMillis()

            val pingController = Ping().dial(node, listenerAddr).controller.join()
            val pingRTTMillis = pingController.ping().join()
            val handshakeEnd = System.currentTimeMillis()
            val handshakePlusOneRTT = handshakeEnd - handshakeStart

            printDiagnosticsLog("Ping latency $pingRTTMillis ms")

            val jsonResult =
                "{\"handshakePlusOneRTTMillis\":${handshakePlusOneRTT.toDouble()}, \"pingRTTMilllis\": ${pingRTTMillis.toDouble()}}"

            emitResult(jsonResult)
            null
        }
    }

    /*
      Start listener and wait up to testTimeoutInSeconds for a message from dialer
     */
    private fun startListener(
        jedis: Jedis,
        advertisedAddress: Multiaddr
    ): CompletableFuture<Void> {
        return CompletableFuture.supplyAsync {
            println("Starting listener with advertisedAddress: $advertisedAddress")

            jedis.rpush(REDIS_KEY_LISTENER_ADDRESS, advertisedAddress.toString())

            // Wait for dialer
            Thread.sleep(params.testTimeoutInSeconds.toLong() * 1000L)
            null
        }
    }

    private fun createProtocols(
        privateKey: PrivKey,
        listenAddresses: ArrayList<String>
    ): ArrayList<ProtocolBinding<Any>> {
        var identifyBuilder =
            IdentifyOuterClass.Identify.newBuilder()
                .setProtocolVersion("ipfs/0.1.0")
                .setAgentVersion("jvm-libp2p/v1.0.0")
                .setPublicKey(privateKey.publicKey().bytes().toProtobuf())
                .addAllListenAddrs(
                    listenAddresses.stream()
                        .map(Multiaddr::fromString)
                        .map(Multiaddr::serialize)
                        .map(ByteArray::toProtobuf)
                        .collect(Collectors.toList())
                )

        val protocols = ArrayList<ProtocolBinding<Any>>()
        protocols.add(Ping())
        for (protocol in protocols) {
            identifyBuilder =
                identifyBuilder.addAllProtocols(protocol.protocolDescriptor.announceProtocols)
        }
        protocols.add(Identify(identifyBuilder.build()))

        return protocols
    }
}

private fun emitResult(json: String) {
    println(json)
}

private fun printDiagnosticsLog(msg: String) {
    System.err.println(msg)
}

@SuppressWarnings("unused")
fun main() {
    try {
        val params = InteropTestParams.Builder().fromEnvironmentVariables().build()

        InteropTestAgent(params).run()
            .orTimeout(params.testTimeoutInSeconds.toLong(), TimeUnit.SECONDS)
            .join()
    } catch (e: Exception) {
        printDiagnosticsLog("Unexpected exit: $e")
        exitProcess(-1)
    }
}
