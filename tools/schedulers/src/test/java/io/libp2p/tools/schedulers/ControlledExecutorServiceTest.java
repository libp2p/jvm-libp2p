package io.libp2p.tools.schedulers;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ControlledExecutorServiceTest {

    TimeControllerImpl timeController = new TimeControllerImpl();
    ControlledExecutorServiceImpl executorService = new ControlledExecutorServiceImpl(timeController);

    @Test
    public void testThatZeroInitialDelayWorks() {
        AtomicInteger executed = new AtomicInteger();
        executorService.scheduleAtFixedRate(executed::incrementAndGet, 0, 1000, TimeUnit.MILLISECONDS);

        timeController.addTime(1);

        Assertions.assertThat(executed).hasValue(1);

        timeController.addTime(998);

        Assertions.assertThat(executed).hasValue(1);

        timeController.addTime(1);

        Assertions.assertThat(executed).hasValue(2);
    }

    @Test
    public void testInitialDelayWorks() {
        AtomicInteger executed = new AtomicInteger();
        executorService.scheduleAtFixedRate(executed::incrementAndGet, 1000, 1000, TimeUnit.MILLISECONDS);

        timeController.addTime(1);
        Assertions.assertThat(executed).hasValue(0);
        timeController.addTime(998);
        Assertions.assertThat(executed).hasValue(0);
        timeController.addTime(1);
        Assertions.assertThat(executed).hasValue(1);
        timeController.addTime(999);
        Assertions.assertThat(executed).hasValue(1);
        timeController.addTime(1);
        Assertions.assertThat(executed).hasValue(2);
    }
}
