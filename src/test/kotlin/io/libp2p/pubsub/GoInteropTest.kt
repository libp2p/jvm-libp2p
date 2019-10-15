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
import io.libp2p.etc.types.defer
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toHex
import io.libp2p.etc.types.toProtobuf
import io.libp2p.etc.util.netty.fromLogHandler
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.protocol.Identify
import io.libp2p.protocol.Ping
import io.libp2p.pubsub.gossip.Gossip
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.tools.P2pdRunner
import io.libp2p.tools.TestChannel
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import io.netty.channel.ChannelHandler
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
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

class GoInteropTest {

    init {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
    }

    @Test
    fun connect1() {
        val logger = LogManager.getLogger("test")
        val daemonLauncher = P2pdRunner().launcher() ?: run {
            println("p2pd executable not found. Skipping this test")
            return
        }
        val identityFileArgs = arrayOf<String>()

        // uncomment the following line and set the generated (with p2p-keygen tool) key file path
        // val identityFileArgs = arrayOf<String>("-id", "E:\\ws\\jvm-libp2p-minimal\\p2pd.key")

        val pdHost = daemonLauncher
            .launch(45555, "-pubsub", *identityFileArgs)
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
                (it.router as GossipRouter).validator = PubsubMessageValidator.nopValidator()
            }

            val applicationProtocols = listOf(ProtocolBinding.createSimple("/meshsub/1.0.0", gossip), Identify())
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
    fun hostTest() = defer { d ->
        val logger = LogManager.getLogger("test")
        val daemonLauncher = P2pdRunner().launcher() ?: run {
            println("p2pd executable not found. Skipping this test")
            return@defer
        }
        val pdHost = daemonLauncher
            .launch(45555, "-pubsub")

        d.defer {
            println("Killing p2pd process")
            pdHost.kill()
        }

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
                +Identify()
                +gossip
            }
            debug {
//                afterSecureHandler.setLogger(LogLevel.ERROR)
                muxFramesHandler.setLogger(LogLevel.ERROR)
            }
        }
        d.defer {
            println("Stopping host")
            host.stop().get(5, TimeUnit.SECONDS)
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

    @Test
    @Disabled
    fun jsInteropTest() {
        val testChannel = TestChannel(
            "test", true,
            LoggingHandler("wire", LogLevel.ERROR),
            ProtobufVarint32FrameDecoder(),
            ProtobufVarint32LengthFieldPrepender(),
            ProtobufDecoder(Rpc.RPC.getDefaultInstance()),
            ProtobufEncoder(),
            LoggingHandler("gossip", LogLevel.ERROR)
        )

        val dump = """
+--------+-------------------------------------------------+----------------+
|00000000| 8f 07 12 8c 07 0a 27 00 25 08 02 12 21 02 63 48 |......'.%...!.cH|
|00000010| 28 39 da 35 80 fa f9 32 72 80 82 28 4f e2 1b 83 |(9.5...2r..(O...|
|00000020| b6 cd 5f 1f 65 2e 4e fd da ed c1 2d a1 30 12 c2 |.._.e.N....-.0..|
|00000030| 05 01 00 00 00 00 00 00 00 2f cc 13 e8 d5 44 e7 |........./....D.|
|00000040| 61 76 cc 22 e3 62 7f 79 16 0f 7d 77 25 35 e3 20 |av.".b.y..}w%5. |
|00000050| 09 ac 63 19 b0 80 18 14 f7 d2 54 dd 22 a5 cb 5e |..c.......T."..^|
|00000060| 0c 65 66 f8 3e 58 4a 23 d9 a2 9e 82 08 2b 3d d5 |.ef.>XJ#.....+=.|
|00000070| 2c 41 ad b1 4a a1 a6 e6 f1 ac 00 00 00 84 cc bd |,A..J...........|
|00000080| 20 d2 f5 d8 98 b3 7d 74 78 59 69 7a 8d e2 c5 54 | .....}txYiz...T|
|00000090| 04 7f 33 c9 e9 ce 7c 5f 80 c3 1a f8 ed 44 70 49 |..3...|_.....DpI|
|000000a0| a9 cf bb 64 1c 5c 84 2a 84 54 47 95 ef 01 51 e4 |...d.\.*.TG...Q.|
|000000b0| 90 82 3b 86 5d 81 ed 9c 29 f0 a9 06 16 5e 82 06 |..;.]...)....^..|
|000000c0| 79 f1 7c de f2 d4 60 79 bd 44 29 3f 2d 73 c6 91 |y.|...`y.D)?-s..|
|000000d0| e7 da d9 e7 31 af 3e f1 ae 9b 5f 35 ad ae 76 67 |....1.>..._5..vg|
|000000e0| ff 68 3a e9 ef a5 ae 5d a4 ce 32 a8 6a d7 74 e4 |.h:....]..2.j.t.|
|000000f0| 69 72 5d 87 3c 71 7d 34 58 22 fb 76 af 2f b2 d3 |ir].<q}4X".v./..|
|00000100| 2c 65 4b f8 96 cf 5d 4b 7b d5 fa 2f 83 06 4d 1d |,eK...]K{../..M.|
|00000110| c3 79 f7 e6 b6 8b 7e 26 29 7b a2 69 a6 49 df fb |.y....~&){.i.I..|
|00000120| f6 df 96 6e 42 a8 3b 18 5e 43 38 48 4c 56 f1 e8 |...nB.;.^C8HLV..|
|00000130| 73 e4 f5 f3 e2 83 72 69 90 b7 0c c8 06 9a f4 15 |s.....ri........|
|00000140| 8d 9f 94 72 1a 17 49 f3 27 6e d1 1b 12 a4 f4 4a |...r..I.'n.....J|
|00000150| 4c 3d 91 bd 35 85 ae e4 ad 09 30 a3 59 08 00 00 |L=..5.....0.Y...|
|00000160| 00 00 00 00 00 2b 32 db 6c 2c 0a 62 35 fb 13 97 |.....+2.l,.b5...|
|00000170| e8 22 5e a8 5e 0f 0e 6e 8c 7b 12 6d 00 16 cc bd |."^.^..n.{.m....|
|00000180| e0 e6 67 15 1e 00 00 00 00 00 00 00 00 00 00 00 |..g.............|
|00000190| 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 |................|
|000001a0| 00 00 00 00 00 e0 00 00 00 e0 00 00 00 e0 00 00 |................|
|000001b0| 00 16 02 00 00 16 02 00 00 16 02 00 00 04 00 00 |................|
|000001c0| 00 30 01 00 00 2f cc 13 e8 d5 44 e7 61 76 cc 22 |.0.../....D.av."|
|000001d0| e3 62 7f 79 16 0f 7d 77 25 35 e3 20 09 ac 63 19 |.b.y..}w%5. ..c.|
|000001e0| b0 80 18 14 f7 00 00 00 00 00 00 00 00 00 00 00 |................|
|000001f0| 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 |................|
|00000200| 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 |................|
|00000210| 00 00 00 00 00 2f cc 13 e8 d5 44 e7 61 76 cc 22 |...../....D.av."|
|00000220| e3 62 7f 79 16 0f 7d 77 25 35 e3 20 09 ac 63 19 |.b.y..}w%5. ..c.|
|00000230| b0 80 18 14 f7 00 00 00 00 00 00 00 00 c7 80 09 |................|
|00000240| fd f0 7f c5 6a 11 f1 22 37 06 58 a3 53 aa a5 42 |....j.."7.X.S..B|
|00000250| ed 63 e4 4c 4b c1 5f f4 cd 10 5a b3 3c 00 00 00 |.c.LK._...Z.<...|
|00000260| 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 |................|
|00000270| 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 |................|
|00000280| 00 00 00 00 00 00 00 00 00 00 00 00 00 31 01 00 |.............1..|
|00000290| 00 96 bd 3d 74 bc eb 6b 34 e4 41 16 52 ad 2f 16 |...=t..k4.A.R./.|
|000002a0| b0 24 5b 92 c2 12 7c 54 65 90 e8 78 04 6c ed b7 |.${'$'}[...|Te..x.l..|
|000002b0| 92 4c 62 4f 75 5b 9a ad 9c 4f fe 83 d0 1d e9 5d |.LbOu[...O.....]|
|000002c0| 44 17 6f 1e 2e 4d e0 6b 85 76 f2 94 11 c5 e7 eb |D.o..M.k.v......|
|000002d0| 06 ae a5 07 10 fb 99 d1 17 df ab 7d 31 20 e4 b2 |...........}1 ..|
|000002e0| 39 55 de a9 53 ce 6f b0 1c 3d 18 31 09 37 32 22 |9U..S.o..=.1.72"|
|000002f0| ab 03 02 1a 14 14 b2 67 6d 0c 65 c3 3c 5a 46 81 |.......gm.e.<ZF.|
|00000300| 3f 37 2c 14 88 33 45 6e 5c 22 16 2f 65 74 68 32 |?7,..3En\"./eth2|
|00000310| 2f 62 65 61 63 6f 6e 5f 62 6c 6f 63 6b 2f 73 73 |/beacon_block/ss|
|00000320| 7a 2a 47 30 45 02 21 00 da 23 b9 94 bd 87 2d 43 |z*G0E.!..#....-C|
|00000330| 42 47 f7 ce 36 f0 a9 93 30 05 9b 82 04 c4 69 88 |BG..6...0.....i.|
|00000340| 75 19 66 cc 22 8d 83 9f 02 20 1b 4b 72 9c df e8 |u.f.".... .Kr...|
|00000350| 86 40 64 49 7e 5a 0c 6f 13 48 44 e9 e9 28 66 2e |.@dI~Z.o.HD..(f.|
|00000360| 89 64 53 a6 1b f8 e1 c9 94 77 32 25 08 02 12 21 |.dS......w2%...!|
|00000370| 02 63 48 28 39 da 35 80 fa f9 32 72 80 82 28 4f |.cH(9.5...2r..(O|
|00000380| e2 1b 83 b6 cd 5f 1f 65 2e 4e fd da ed c1 2d a1 |....._.e.N....-.|
|00000390| 30                                              |0               |
+--------+-------------------------------------------------+----------------+
        """.trimIndent()

        val bytes = dump.fromLogHandler()
        testChannel.writeInbound(bytes.toByteBuf())
        val psMsg = testChannel.readInbound<Rpc.RPC>()
        PubsubMessageValidator.signatureValidator().validate(psMsg)
//        val seqNo = psMsg.publishList[0].seqno

        println(psMsg.publishList[0].data.toByteArray().toHex())
    }
}