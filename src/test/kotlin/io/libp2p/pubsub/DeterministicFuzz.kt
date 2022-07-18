package io.libp2p.pubsub

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.etc.types.lazyVar
import io.libp2p.pubsub.flood.FloodRouter
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.ScheduledExecutorService

typealias DeterministicFuzzRouterFactory = (ScheduledExecutorService, CurrentTimeSupplier, Random) -> PubsubRouterDebug

class DeterministicFuzz {

    var cnt = 0
    val timeController = TimeControllerImpl()
    var randomSeed by lazyVar { 777L }
    val random by lazyVar { Random(randomSeed) }

    fun createControlledExecutor(): ScheduledExecutorService =
        ControlledExecutorServiceImpl().also { it.setTimeController(timeController) }

    fun createTestGossipRouter(gossipRouterBuilder: () -> GossipRouterBuilder): TestRouter =
        createTestRouter(createGossipFuzzRouterFactory(gossipRouterBuilder))

    fun createMockRouter() = createTestRouter(createMockFuzzRouterFactory())
    fun createFloodRouter() = createTestRouter(createFloodFuzzRouterFactory())

    fun createTestRouter(routerCtor: DeterministicFuzzRouterFactory): TestRouter {
        val deterministicExecutor = createControlledExecutor()
        val router = routerCtor(deterministicExecutor, { timeController.time }, random)

        return TestRouter("" + (cnt++), router).apply {
            val randomBytes = ByteArray(8)
            random.nextBytes(randomBytes)
            keyPair = generateKeyPair(KEY_TYPE.ECDSA, random = SecureRandom(randomBytes))
            testExecutor = deterministicExecutor
        }
    }

    companion object {
        fun createGossipFuzzRouterFactory(routerBuilderFactory: () -> GossipRouterBuilder): DeterministicFuzzRouterFactory =
            { executor, curTime, random ->
                routerBuilderFactory().also {
                    it.scheduledAsyncExecutor = executor
                    it.currentTimeSuppluer = curTime
                    it.random = random
                }.build()
            }

        fun createMockFuzzRouterFactory(): DeterministicFuzzRouterFactory =
            { executor, _, _ ->
                MockRouter(executor)
            }

        fun createFloodFuzzRouterFactory(): DeterministicFuzzRouterFactory =
            { executor, _, _ ->
                FloodRouter(executor)
            }
    }
}
