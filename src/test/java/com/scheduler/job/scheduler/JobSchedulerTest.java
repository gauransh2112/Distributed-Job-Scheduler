package com.scheduler.job.scheduler;

import com.scheduler.job.config.SchedulerProperties;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.exception.QueuePublishException;
import com.scheduler.job.repository.JobRepository;
import com.scheduler.job.queue.QueuePublisher;
import com.scheduler.job.service.JobClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the polling loop's own behaviour: what it delegates, what it must not do, and that a
 * failing poll cannot kill the schedule.
 */
@ExtendWith(MockitoExtension.class)
class JobSchedulerTest {

    private static final long POLL_INTERVAL_MS = 5000;
    private static final int BATCH_SIZE = 25;
    private static final String INSTANCE_ID = "scheduler-test-1";

    @Mock
    private JobClaimService jobClaimService;

    private SchedulerProperties schedulerProperties;
    private JobScheduler jobScheduler;

    @BeforeEach
    void setUp() {
        schedulerProperties = new SchedulerProperties(POLL_INTERVAL_MS, BATCH_SIZE, INSTANCE_ID);
        jobScheduler = new JobScheduler(jobClaimService, schedulerProperties);
    }

    @Test
    @DisplayName("poll delegates to the claim service using the configured batch size and instance id")
    void testPollDelegatesWithConfiguredValues() {
        when(jobClaimService.claimJobs(BATCH_SIZE, INSTANCE_ID)).thenReturn(List.of(job(), job()));

        jobScheduler.poll();

        verify(jobClaimService).claimJobs(BATCH_SIZE, INSTANCE_ID);
        verifyNoMoreInteractions(jobClaimService);
    }

    @Test
    @DisplayName("poll picks up configuration changes rather than caching values from construction")
    void testPollReadsConfigurationOnEveryPoll() {
        when(jobClaimService.claimJobs(BATCH_SIZE, INSTANCE_ID)).thenReturn(List.of());
        jobScheduler.poll();

        schedulerProperties.setBatchSize(7);
        schedulerProperties.setInstanceId("scheduler-test-2");
        when(jobClaimService.claimJobs(7, "scheduler-test-2")).thenReturn(List.of());
        jobScheduler.poll();

        verify(jobClaimService).claimJobs(BATCH_SIZE, INSTANCE_ID);
        verify(jobClaimService).claimJobs(7, "scheduler-test-2");
    }

    @Test
    @DisplayName("poll handles an empty claim result without error")
    void testPollWithNoDueJobs() {
        when(jobClaimService.claimJobs(BATCH_SIZE, INSTANCE_ID)).thenReturn(List.of());

        assertDoesNotThrow(() -> jobScheduler.poll());

        verify(jobClaimService).claimJobs(BATCH_SIZE, INSTANCE_ID);
    }

    @Test
    @DisplayName("A failing poll does not propagate, so the schedule survives and polls again")
    void testFailingPollDoesNotKillTheSchedule() {
        when(jobClaimService.claimJobs(BATCH_SIZE, INSTANCE_ID))
                .thenThrow(new QueuePublishException("SQS unavailable"))
                .thenReturn(List.of(job()));

        // The claim transaction already rolled back, leaving the jobs PENDING; the documented recovery
        // is to retry on the next poll, so the exception must not escape the scheduled method.
        assertDoesNotThrow(() -> jobScheduler.poll());
        assertDoesNotThrow(() -> jobScheduler.poll());

        verify(jobClaimService, times(2)).claimJobs(BATCH_SIZE, INSTANCE_ID);
    }

    @Test
    @DisplayName("A database failure during a poll is contained the same way")
    void testDatabaseFailureIsContained() {
        when(jobClaimService.claimJobs(BATCH_SIZE, INSTANCE_ID))
                .thenThrow(new IllegalStateException("connection pool exhausted"));

        assertDoesNotThrow(() -> jobScheduler.poll());
    }

    @Test
    @DisplayName("The scheduler depends only on the claim service and its configuration")
    void testSchedulerHasNoForbiddenDependencies() {
        // The scheduler must not execute handlers, publish to SQS, or reach the repository directly.
        // Those would have to arrive as collaborators, so the field types are the check.
        for (Field field : JobScheduler.class.getDeclaredFields()) {
            if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            assertTrue(type == JobClaimService.class || type == SchedulerProperties.class,
                    "Unexpected scheduler dependency: " + type.getName());
            assertFalse(JobRepository.class.isAssignableFrom(type), "Scheduler must not access the repository");
            assertFalse(QueuePublisher.class.isAssignableFrom(type), "Scheduler must not access the queue");
        }
    }

    @Test
    @DisplayName("The scheduler refuses to be constructed without its collaborators")
    void testRequiredCollaborators() {
        assertThrows(NullPointerException.class, () -> new JobScheduler(null, schedulerProperties));
        assertThrows(NullPointerException.class, () -> new JobScheduler(jobClaimService, null));
    }

    private JobEntity job() {
        return JobEntity.builder().id(UUID.randomUUID()).jobType("SEND_EMAIL").build();
    }
}
