package io.libp2p.pubsub

import io.libp2p.core.Connection
import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolBindingInitializer
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.mux.mplex.MplexStreamMuxer
import io.libp2p.core.security.secio.SecIoSecureChannel
import io.libp2p.core.transport.ConnectionUpgrader
import io.libp2p.core.transport.tcp.TcpTransport
import io.libp2p.core.types.fromHex
import io.libp2p.core.types.toByteBuf
import io.libp2p.core.types.toHex
import io.libp2p.core.types.toProtobuf
import io.libp2p.core.util.netty.nettyInitializer
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.tools.p2pd.DaemonLauncher
import io.netty.channel.ChannelHandler
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GossipProtocolBinding(val router: PubsubRouterDebug) : ProtocolBinding<Unit> {
    var debugGossipHandler: ChannelHandler? = null
    override val announce = "/meshsub/1.0.0"
    override val matcher = ProtocolMatcher(Mode.STRICT, announce)
    override fun initializer(selectedProtocol: String): ProtocolBindingInitializer<Unit> {
        val future = CompletableFuture<Unit>()
        val pubsubInitializer = nettyInitializer { ch ->
            router.addPeerWithDebugHandler(Stream(ch, Connection(ch.parent())), debugGossipHandler)
            future.complete(null)
        }
        return ProtocolBindingInitializer(pubsubInitializer, future)
    }
}

class RemoteTest {

    @Test
    @Disabled
    fun connect1() {
        val logger = LogManager.getLogger("test")
        val pdHost = DaemonLauncher("C:\\Users\\Admin\\go\\bin\\p2pd.exe")
            .launch(45555, "-pubsub")

//        Thread.sleep(1000)
//        println("Subscribing Go..")
//        pdHost.host.pubsub.subscribe("topic1").get()
        val (privKey1, pubKey1) = generateKeyPair(KEY_TYPE.ECDSA)

        val gossipRouter = GossipRouter().also {
            it.validator = PubsubMessageValidator.signatureValidator()
        }
        gossipRouter.subscribe("topic1")
        val pubsubApi = createPubsubApi(gossipRouter)
        val publisher = pubsubApi.createPublisher(privKey1, 8888)

        val upgrader = ConnectionUpgrader(
            listOf(SecIoSecureChannel(privKey1)),
            listOf(MplexStreamMuxer().also {
                it.intermediateFrameHandler = LoggingHandler("#3", LogLevel.INFO)
            })
        ).also {
//                it.beforeSecureHandler = LoggingHandler("#1", LogLevel.INFO)
                it.afterSecureHandler = LoggingHandler("#2", LogLevel.INFO)
            }

        val tcpTransport = TcpTransport(upgrader)
        val gossipLisener = GossipProtocolBinding(gossipRouter).also {
            it.debugGossipHandler = LoggingHandler("#4", LogLevel.INFO)
        }

        val applicationProtocols = listOf(gossipLisener)
        val inboundStreamHandler = StreamHandler.create(Multistream.create(applicationProtocols))
        logger.info("Dialing...")
        val connFuture = tcpTransport.dial(Multiaddr("/ip4/127.0.0.1/tcp/45555"), inboundStreamHandler)

        connFuture.thenApply {
            logger.info("Connection made")
            val gossipDialer = GossipProtocolBinding(gossipRouter).also {
                it.debugGossipHandler = LoggingHandler("#4'", LogLevel.INFO)
            }
            val initiator = Multistream.create(listOf(gossipDialer))
            val (channelHandler, completableFuture) = initiator.initializer()
            logger.info("Creating stream")
            it.muxerSession.get().createStream(StreamHandler.create(channelHandler))
            completableFuture
        }.thenAccept {
            logger.info("Stream created, sending echo string...")
        }.get(5, TimeUnit.HOURS)
        logger.info("Success!")

        Thread.sleep(2000)
        println("Subscribing Go..")
        val msgQueue = pdHost.host.pubsub.subscribe("topic1").get()
        Thread {
            while (true) {
                val psMessage = msgQueue.take()
                println("Message received by p2pd: $psMessage")
                println("From: " + psMessage.from.toByteArray().toHex())
                println("Seq: " + psMessage.seqno.toByteArray().toHex())
                println("Sig: " + psMessage.signature.toByteArray().toHex())
                println("Key: " + psMessage.key.toByteArray().toHex())
            }
        }.start()
        Thread.sleep(2000)
        println("Sending msg from Go..")
        pdHost.host.pubsub.publish("topic1", ByteArray(10)).get()
        Thread.sleep(2000)
        println("Sending msg from Java..")
        publisher.publish("Hello".toByteArray().toByteBuf(), Topic("topic1"))
//        gossipRouter.publish(Rpc.Message.newBuilder().addTopicIDs("topic1").setData(ByteArray(1000).toProtobuf()).build())

        println("Waiting")
        Thread.sleep(5000)
        pdHost.kill()
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