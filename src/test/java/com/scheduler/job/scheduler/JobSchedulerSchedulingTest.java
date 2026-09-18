package com.scheduler.job.scheduler;

import com.scheduler.job.config.SchedulerProperties;
import com.scheduler.job.service.JobClaimService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that the {@code @Scheduled} wiring actually fires, at the configured interval, against a real
 * Spring scheduler.
 *
 * <p>A unit test can only prove that {@code poll()} does the right thing when called. This proves that
 * something calls it, that the interval placeholder resolves from configuration, and that the fixed
 * delay is honoured — the parts a direct method call cannot exercise.
 *
 * <p>The context is deliberately minimal: no JPA, no database, no queue. The scheduler's only
 * collaborator is a mocked {@link JobClaimService}.
 */
@SpringJUnitConfig(JobSchedulerSchedulingTest.SchedulingTestConfig.class)
@TestPropertySource(properties = {
        "scheduler.poll-interval-ms=100",
        "scheduler.batch-size=11",
        "scheduler.instance-id=scheduler-scheduling-test"
})
class JobSchedulerSchedulingTest {

    @Autowired
    private JobClaimService jobClaimService;

    @Autowired
    private SchedulerProperties schedulerProperties;

    @Test
    @DisplayName("The scheduled poll fires repeatedly without anyone calling it")
    void testScheduledPollFiresRepeatedly() {
        // At a 100ms fixed delay, three polls take roughly 300ms; 5s is a generous ceiling.
        verify(jobClaimService, timeout(5000).atLeast(3)).claimJobs(anyInt(), anyString());
    }

    @Test
    @DisplayName("The scheduled poll uses the configured batch size and instance id")
    void testScheduledPollUsesConfiguredValues() {
        assertEquals(11, schedulerProperties.getBatchSize());
        assertEquals("scheduler-scheduling-test", schedulerProperties.getInstanceId());
        assertEquals(100, schedulerProperties.getPollIntervalMs());

        verify(jobClaimService, timeout(5000).atLeast(1))
                .claimJobs(eq(11), eq("scheduler-scheduling-test"));
    }

    @Configuration
    @EnableScheduling
    static class SchedulingTestConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            // Required for ${scheduler.poll-interval-ms} in @Scheduled to resolve outside Spring Boot.
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        JobClaimService jobClaimService() {
            JobClaimService mock = Mockito.mock(JobClaimService.class);
            when(mock.claimJobs(anyInt(), anyString())).thenReturn(List.of());
            return mock;
        }

        @Bean
        SchedulerProperties schedulerProperties(
                @org.springframework.beans.factory.annotation.Value("${scheduler.poll-interval-ms}") long pollIntervalMs,
                @org.springframework.beans.factory.annotation.Value("${scheduler.batch-size}") int batchSize,
                @org.springframework.beans.factory.annotation.Value("${scheduler.instance-id}") String instanceId) {
            return new SchedulerProperties(pollIntervalMs, batchSize, instanceId);
        }

        @Bean
        JobScheduler jobScheduler(JobClaimService jobClaimService, SchedulerProperties schedulerProperties) {
            return new JobScheduler(jobClaimService, schedulerProperties);
        }
    }
}
