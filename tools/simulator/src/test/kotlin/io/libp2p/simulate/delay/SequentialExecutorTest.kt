package io.libp2p.simulate.delay

import io.libp2p.simulate.util.scheduleCompletable
import io.libp2p.tools.schedule
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class SequentialExecutorTest {
    val timeController = TimeControllerImpl()
    val executor = ControlledExecutorServiceImpl(timeController)

    @Test
    fun test1() {
        val preservingExecutor = OrderPreservingExecutor()
        val f1 = executor.scheduleCompletable(200.milliseconds) { 1 }
        val f2 = executor.scheduleCompletable(50.milliseconds) { 2 }
        val f3 = executor.scheduleCompletable(50.milliseconds) { 3 }
        val f4 = executor.scheduleCompletable(250.milliseconds) { 4 }

        val pf1 = preservingExecutor
            .enqueue(f1)
            .thenApply { timeController.time to it }
        val pf2 = preservingExecutor
            .enqueue(f2)
            .thenApply { timeController.time to it }
        val pf3 = preservingExecutor
            .enqueue(f3)
            .thenApply { timeController.time to it }
        val pf4 = preservingExecutor
            .enqueue(f4)
            .thenApply { timeController.time to it }

        timeController.addTime(1.minutes.toJavaDuration())

        Assertions.assertThat(pf1.get()).isEqualTo(200L to 1)
        Assertions.assertThat(pf2.get()).isEqualTo(200L to 2)
        Assertions.assertThat(pf3.get()).isEqualTo(200L to 3)
        Assertions.assertThat(pf4.get()).isEqualTo(250L to 4)

    }
}