package com.scheduler.job.scheduler;

import com.scheduler.job.config.SchedulerProperties;
import com.scheduler.job.service.JobClaimService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the polling loop's on/off switch.
 *
 * <p>The loop is on by default, so a deployment cannot accidentally end up with no scheduler at all,
 * and turning it off requires an explicit {@code scheduler.enabled=false}. Tests rely on that switch to
 * keep a background poller from racing their assertions.
 */
class JobSchedulerWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(JobScheduler.class))
            .withBean(JobClaimService.class, () -> Mockito.mock(JobClaimService.class))
            .withBean(SchedulerProperties.class, () -> new SchedulerProperties(5000, 50, "scheduler-wiring-test"));

    @Test
    @DisplayName("The polling loop is wired by default")
    void testSchedulerEnabledByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(JobScheduler.class));
    }

    @Test
    @DisplayName("Explicitly enabling the polling loop wires it")
    void testSchedulerWhenExplicitlyEnabled() {
        contextRunner.withPropertyValues("scheduler.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(JobScheduler.class));
    }

    @Test
    @DisplayName("The polling loop is removed only by an explicit opt-out")
    void testSchedulerRequiresExplicitOptOut() {
        contextRunner.withPropertyValues("scheduler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(JobScheduler.class));
    }
}
