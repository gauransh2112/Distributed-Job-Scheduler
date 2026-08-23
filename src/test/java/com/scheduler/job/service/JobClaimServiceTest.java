package com.scheduler.job.service;

import com.scheduler.job.config.SchedulerProperties;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.QueuePublishException;
import com.scheduler.job.queue.QueuePublisher;
import com.scheduler.job.repository.JobRepository;
import com.scheduler.job.service.impl.JobClaimServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobClaimServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobStateService jobStateService;

    @Mock
    private QueuePublisher queuePublisher;

    private SchedulerProperties schedulerProperties;
    private Clock fixedClock;
    private JobClaimService jobClaimService;

    @BeforeEach
    void setUp() {
        schedulerProperties = new SchedulerProperties(5000, 10, "node-test-1");
        fixedClock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneId.of("UTC"));
        jobClaimService = new JobClaimServiceImpl(jobRepository, jobStateService, queuePublisher, schedulerProperties, fixedClock);
    }

    @Test
    @DisplayName("claimJobs - Successful claim of due jobs using injected Clock and publication to queue")
    void claimJobs_Success() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        JobEntity pending1 = createJob(id1, JobStatus.PENDING);
        JobEntity pending2 = createJob(id2, JobStatus.PENDING);

        JobEntity claimed1 = createJob(id1, JobStatus.CLAIMED);
        claimed1.setClaimedBy("node-test-1");
        JobEntity claimed2 = createJob(id2, JobStatus.CLAIMED);
        claimed2.setClaimedBy("node-test-1");

        when(jobRepository.findEligibleJobsForClaim(fixedClock.instant(), 10))
                .thenReturn(List.of(pending1, pending2));
        when(jobStateService.markClaimed(id1, "node-test-1")).thenReturn(claimed1);
        when(jobStateService.markClaimed(id2, "node-test-1")).thenReturn(claimed2);

        List<JobEntity> result = jobClaimService.claimJobs();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(JobStatus.CLAIMED, result.get(0).getStatus());
        assertEquals(JobStatus.CLAIMED, result.get(1).getStatus());

        verify(jobStateService, times(1)).markClaimed(id1, "node-test-1");
        verify(jobStateService, times(1)).markClaimed(id2, "node-test-1");
        verify(queuePublisher, times(1)).publish(claimed1);
        verify(queuePublisher, times(1)).publish(claimed2);
    }

    @Test
    @DisplayName("claimJobs - Invalid parameters fall back to SchedulerProperties defaults")
    void claimJobs_InvalidParametersFallbackToDefaults() {
        UUID id1 = UUID.randomUUID();
        JobEntity pending1 = createJob(id1, JobStatus.PENDING);
        JobEntity claimed1 = createJob(id1, JobStatus.CLAIMED);

        when(jobRepository.findEligibleJobsForClaim(fixedClock.instant(), 10))
                .thenReturn(List.of(pending1));
        when(jobStateService.markClaimed(id1, "node-test-1")).thenReturn(claimed1);

        // Pass invalid -5 batch size and blank instanceId
        List<JobEntity> result = jobClaimService.claimJobs(-5, "   ");

        assertEquals(1, result.size());
        verify(jobRepository).findEligibleJobsForClaim(fixedClock.instant(), 10);
        verify(jobStateService).markClaimed(id1, "node-test-1");
    }

    @Test
    @DisplayName("claimJobs - Returns empty list when no due jobs found")
    void claimJobs_EmptyResult() {
        when(jobRepository.findEligibleJobsForClaim(any(Instant.class), anyInt()))
                .thenReturn(List.of());

        List<JobEntity> result = jobClaimService.claimJobs();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(jobStateService, never()).markClaimed(any(), any());
        verify(queuePublisher, never()).publish(any());
    }

    @Test
    @DisplayName("claimJobs - Queue publication failure throws QueuePublishException for rollback")
    void claimJobs_QueueFailure_ThrowsExceptionForRollback() {
        UUID id1 = UUID.randomUUID();
        JobEntity pending1 = createJob(id1, JobStatus.PENDING);
        JobEntity claimed1 = createJob(id1, JobStatus.CLAIMED);

        when(jobRepository.findEligibleJobsForClaim(fixedClock.instant(), 5))
                .thenReturn(List.of(pending1));
        when(jobStateService.markClaimed(id1, "node-custom")).thenReturn(claimed1);
        doThrow(new QueuePublishException("SQS timeout"))
                .when(queuePublisher).publish(claimed1);

        assertThrows(QueuePublishException.class, () -> jobClaimService.claimJobs(5, "node-custom"));

        verify(jobStateService, times(1)).markClaimed(id1, "node-custom");
        verify(queuePublisher, times(1)).publish(claimed1);
    }

    private JobEntity createJob(UUID id, JobStatus status) {
        return JobEntity.builder()
                .id(id)
                .jobType("GENERATE_REPORT")
                .payload("{}")
                .status(status)
                .scheduledAt(fixedClock.instant().minusSeconds(10))
                .retryCount(0)
                .maxRetries(5)
                .build();
    }
}
