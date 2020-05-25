package io.libp2p.pubsub

import io.libp2p.etc.types.lazyVar
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import java.util.Random
import java.util.concurrent.ScheduledExecutorService

class DeterministicFuzz {

    var cnt = 0
    val timeController = TimeControllerImpl()
    var randomSeed by lazyVar { 777L }
    val random by lazyVar { Random(randomSeed) }

    fun createControlledExecutor(): ScheduledExecutorService =
        ControlledExecutorServiceImpl().also { it.setTimeController(timeController) }

    fun createTestRouter(
        routerInstance: PubsubRouterDebug,
        protocol: PubsubProtocol = routerInstance.protocol
    ): TestRouter {
        routerInstance.curTime = { timeController.time }
        routerInstance.random = this.random
        val testRouter = TestRouter("" + (cnt++), protocol.announceStr)
        testRouter.routerInstance = routerInstance
        testRouter.testExecutor = createControlledExecutor()
        return testRouter
    }
}