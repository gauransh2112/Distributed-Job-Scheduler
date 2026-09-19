package com.scheduler.job.worker;

import com.scheduler.job.config.SchedulerProperties;
import com.scheduler.job.handler.HandlerRegistry;
import com.scheduler.job.queue.QueueConsumer;
import com.scheduler.job.service.JobStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the worker loop's on/off switch.
 *
 * <p>The loop is on by default, so a deployment cannot silently end up consuming nothing, and turning
 * it off requires an explicit {@code worker.enabled=false}. Tests rely on that switch so a background
 * worker cannot execute the job rows they set up.
 */
class JobWorkerWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(JobWorker.class))
            .withBean(QueueConsumer.class, () -> Mockito.mock(QueueConsumer.class))
            .withBean(JobStateService.class, () -> Mockito.mock(JobStateService.class))
            .withBean(HandlerRegistry.class, () -> new HandlerRegistry(List.of()))
            .withBean(SchedulerProperties.class, () -> new SchedulerProperties(5000, 50, "worker-wiring-test"));

    @Test
    @DisplayName("The worker loop is wired by default")
    void testWorkerEnabledByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(JobWorker.class));
    }

    @Test
    @DisplayName("Explicitly enabling the worker loop wires it")
    void testWorkerWhenExplicitlyEnabled() {
        contextRunner.withPropertyValues("worker.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(JobWorker.class));
    }

    @Test
    @DisplayName("The worker loop is removed only by an explicit opt-out")
    void testWorkerRequiresExplicitOptOut() {
        contextRunner.withPropertyValues("worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(JobWorker.class));
    }
}
