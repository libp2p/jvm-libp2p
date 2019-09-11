package io.libp2p.pubsub

import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.P2PAbstractHandler
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toProtobuf
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.protocol.Ping
import io.libp2p.pubsub.gossip.Gossip
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.tools.p2pd.DaemonLauncher
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandler
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.ResourceLeakDetector
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class GossipProtocol(val router: PubsubRouterDebug) : P2PAbstractHandler<Unit> {
    var debugGossipHandler: ChannelHandler? = null

    override fun initChannel(ch: P2PAbstractChannel): CompletableFuture<out Unit> {
        router.addPeerWithDebugHandler(ch as Stream, debugGossipHandler)
        return CompletableFuture.completedFuture(Unit)
    }
}

const val libp2pdPath = "C:\\Users\\Admin\\go\\bin\\p2pd.exe"

class GoInteropTest {

    init {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
    }

    @Test
    @Disabled
    fun connect1() {
        val logger = LogManager.getLogger("test")
        val pdHost = DaemonLauncher(libp2pdPath)
            .launch(45555, "-pubsub", "-id", "E:\\ws\\jvm-libp2p-minimal\\p2pd.key")
        val pdPeerId = PeerId(pdHost.host.myId.idBytes)
        println("Remote peerID: $pdPeerId")

        try {

            val (privKey1, pubKey1) = generateKeyPair(KEY_TYPE.SECP256K1)

            println("Local peerID: " + PeerId.fromPubKey(pubKey1).toBase58())

            val gossipRouter = GossipRouter().also {
                it.validator = PubsubMessageValidator.signatureValidator()
            }
            val pubsubApi = createPubsubApi(gossipRouter)
            val publisher = pubsubApi.createPublisher(privKey1, 8888)

            val upgrader = ConnectionUpgrader(
                listOf(SecIoSecureChannel(privKey1)),
                listOf(MplexStreamMuxer().also {
                    it.muxFramesDebugHandler = LoggingHandler("#3", LogLevel.ERROR)
                })
            ).also {
                //                it.beforeSecureHandler = LoggingHandler("#1", LogLevel.INFO)
                it.afterSecureHandler = LoggingHandler("#2", LogLevel.INFO)
            }

            val tcpTransport = TcpTransport(upgrader)
            val gossip = GossipProtocol(gossipRouter).also {
                it.debugGossipHandler = LoggingHandler("#4", LogLevel.INFO)
            }

            val applicationProtocols = listOf(ProtocolBinding.createSimple("/meshsub/1.0.0", gossip))
            val inboundStreamHandler = StreamHandler.create(Multistream.create(applicationProtocols))
            logger.info("Dialing...")
            val connFuture = tcpTransport.dial(
                Multiaddr("/ip4/127.0.0.1/tcp/45555" + "/p2p/$pdPeerId"),
                ConnectionHandler.createStreamHandlerInitializer(inboundStreamHandler)
            )

            var pingRes: Long? = null
            connFuture.thenCompose {
                logger.info("Connection made")
                val ret = it.muxerSession.createStream(Multistream.create(applicationProtocols).toStreamHandler()).controler

                val initiator = Multistream.create(Ping())
                logger.info("Creating ping stream")
                it.muxerSession.createStream(initiator.toStreamHandler())
                    .controler.thenCompose {
                        println("Sending ping...")
                        it.ping()
                    }.thenAccept {
                        println("Ping time: $it")
                        pingRes = it
                    }

                ret
            }.thenAccept {
                logger.info("Stream created")
            }.get(5, TimeUnit.HOURS)
            logger.info("Success!")

            Thread.sleep(1000)
            val javaInbound = LinkedBlockingQueue<MessageApi>()
            println("Subscribing Java..")
            pubsubApi.subscribe(Consumer { javaInbound += it }, Topic("topic1"))
            println("Subscribing Go..")
            val goInbound = pdHost.host.pubsub.subscribe("topic1").get()
            Thread.sleep(1000)
            println("Sending msg from Go..")
            val msgFromGo = "Go rocks! JVM sucks!"
            pdHost.host.pubsub.publish("topic1", msgFromGo.toByteArray()).get()
            val msg1 = javaInbound.poll(5, TimeUnit.SECONDS)
            Assertions.assertNotNull(msg1)
            Assertions.assertNull(javaInbound.poll())
            Assertions.assertEquals(msgFromGo, msg1!!.data.toByteArray().toString(StandardCharsets.UTF_8))

            // draining message which Go (by mistake or by design) replays back to subscriber
            goInbound.poll(1, TimeUnit.SECONDS)

            println("Sending msg from Java..")
            val msgFromJava = "Go suck my duke"
            publisher.publish(msgFromJava.toByteArray().toByteBuf(), Topic("topic1"))
            val msg2 = goInbound.poll(5, TimeUnit.SECONDS)
            Assertions.assertNotNull(msg2)
            Assertions.assertNull(goInbound.poll())
            Assertions.assertEquals(msgFromJava, msg2!!.data.toByteArray().toString(StandardCharsets.UTF_8))
            Assertions.assertNotNull(pingRes)

            println("Done!")

            // Allows to detect Netty leaks
            System.gc()
            Thread.sleep(500)
            System.gc()
            Thread.sleep(500)
            System.gc()
        } finally {
            println("Killing p2pd process")
            pdHost.kill()
        }

        // Uncomment to get more details on Netty leaks
//        while(true) {
//            Thread.sleep(500)
//            System.gc()
//        }
    }

    @Test
    @Disabled
    fun hostTest() {
        val logger = LogManager.getLogger("test")
        val pdHost = DaemonLauncher(libp2pdPath)
            .launch(45555, "-pubsub")

        try {

            val gossip = Gossip()

            // Let's create a host! This is a fluent builder.
            val host = host {
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
                addressBook {
                    memory()
                }
                network {
                    listen("/ip4/0.0.0.0/tcp/4001")
                }
                protocols {
                    +Ping()
                    +gossip
                }
            }

            host.start().get(5, TimeUnit.SECONDS)
            println("Host started")

            val connFuture = host.network.connect(PeerId.random(), Multiaddr("/ip4/127.0.0.1/tcp/45555"))

            connFuture.thenAccept {
                logger.info("Connection made")
            }.get(5, TimeUnit.HOURS)

            Thread.sleep(1000)
            val javaInbound = LinkedBlockingQueue<MessageApi>()
            println("Subscribing Java..")
            gossip.subscribe(Consumer { javaInbound += it }, Topic("topic1"))
            println("Subscribing Go..")
            val goInbound = pdHost.host.pubsub.subscribe("topic1").get()
            Thread.sleep(1000)
            println("Sending msg from Go..")
            val msgFromGo = "Go rocks! JVM sucks!"
            pdHost.host.pubsub.publish("topic1", msgFromGo.toByteArray()).get()
            val msg1 = javaInbound.poll(5, TimeUnit.SECONDS)
            Assertions.assertNotNull(msg1)
            Assertions.assertNull(javaInbound.poll())
            Assertions.assertEquals(msgFromGo, msg1!!.data.toByteArray().toString(StandardCharsets.UTF_8))

            // draining message which Go (by mistake or by design) replays back to subscriber
            goInbound.poll(1, TimeUnit.SECONDS)

            println("Sending msg from Java..")
            val msgFromJava = "Go suck my duke"
            val publisher = gossip.createPublisher(host.privKey, 8888)
            publisher.publish(msgFromJava.toByteArray().toByteBuf(), Topic("topic1"))
            val msg2 = goInbound.poll(5, TimeUnit.SECONDS)
            Assertions.assertNotNull(msg2)
            Assertions.assertNull(goInbound.poll())
            Assertions.assertEquals(msgFromJava, msg2!!.data.toByteArray().toString(StandardCharsets.UTF_8))

            println("Done!")

            // Allows to detect Netty leaks
            System.gc()
            Thread.sleep(500)
            System.gc()
            Thread.sleep(500)
            System.gc()
        } finally {
            println("Killing p2pd process")
            pdHost.kill()
        }

        // Uncomment to get more details on Netty leaks
//        while(true) {
//            Thread.sleep(500)
//            System.gc()
//        }
    }

    @Test
    fun sigTest() {
        val fromS = "12201133e39444593a3f91c45aba4f44099fc7246866af9917f8648160180b3ec6ac"
        val seqS = "15ba3296062cc5d9"
        val msg = Rpc.Message.newBuilder()
            .setFrom(fromS.fromHex().toProtobuf())
            .addTopicIDs("topic1")
            .setData(ByteArray(10).toProtobuf())
            .setSeqno(seqS.fromHex().toProtobuf())
            .build()
        val sigS = "6a02496047297b019d027cde79e74c9bb95a341aa8e45b473d44684229028f6ec34fe97145399fe5e07dcb653110ff1d8cfb41e0747bd94880321005feb1d97bfa6db4850aa9b364cc1d4943644b5b8d7644ca47fbf3c44264eb3d4e474675a44caa83c196b7257cdff8dbef050326d3b4e739eea09b9c8e39027513fd7d842e13f861735a1cccadbc211137f1c119d84d260daade5acc9c78dda31f550bf569b6fdb90402ababece7832b2058967d5268249898eaae9a56988b304c229e159b61952f5e6a46758447bb06274d1069bdc1865ce3d2f0a406be3236b38b96502e888c23b84190ab972637011e572031ea97747d7e1bad3bd1a4f5643ed6f9990f"
        val keyS = "080012a60230820122300d06092a864886f70d01010105000382010f003082010a0282010100baa3fd95db3f6179ce6b0f1c0c130f2fafbb3ddb20b77bac8a1a408c84af6e3de7dc09dc74cc117360ec6100fe146b7e1a298a546aa8b7b2e1de81780cc0bf888b53bf9cb5fc8145b83b34a6eb93fa41e15d5e03bb492d87f9d76b6b3b77f2d7c879cf1715ce2bde1552050f3556d42fb466e7a5eb2b9fd74f8c6dad741d4dcfde046173cb0385c498a781ea5bccb253175868384f32ac9b2579374d2e9a187acba3abb4f16a5c01c6cbfafddfb75793062e3b7a5c753e6fdfa6f7c7654466f33164680c37545a3954fd1636fdc985f6fd2237f96c949d492df0ad7686f9a72760182d3264103825e4277e1f68c03b906b3e747d5a73b6673c73890128c565170203010001"
        val pubKey = unmarshalPublicKey(keyS.fromHex())

        val vRes = pubKey.verify("libp2p-pubsub:".toByteArray() + msg.toByteArray(), sigS.fromHex())
        println("$pubKey: $vRes")
    }
}