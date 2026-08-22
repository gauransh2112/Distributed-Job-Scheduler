package com.scheduler.job.service;

import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.InvalidStateTransitionException;
import com.scheduler.job.exception.JobNotFoundException;
import com.scheduler.job.repository.JobRepository;
import com.scheduler.job.service.impl.JobStateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobStateServiceTest {

    @Mock
    private JobRepository jobRepository;

    private JobStateService jobStateService;

    @BeforeEach
    void setUp() {
        jobStateService = new JobStateServiceImpl(jobRepository);
    }

    @Test
    @DisplayName("markClaimed - Success: PENDING -> CLAIMED")
    void markClaimed_LegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.PENDING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));
        when(jobRepository.save(any(JobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JobEntity result = jobStateService.markClaimed(jobId, "scheduler-node-1");

        assertNotNull(result);
        assertEquals(JobStatus.CLAIMED, result.getStatus());
        assertEquals("scheduler-node-1", result.getClaimedBy());
        verify(jobRepository).save(entity);
    }

    @Test
    @DisplayName("markClaimed - IllegalTransition: RUNNING -> CLAIMED throws InvalidStateTransitionException")
    void markClaimed_IllegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.RUNNING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));

        InvalidStateTransitionException ex = assertThrows(
                InvalidStateTransitionException.class,
                () -> jobStateService.markClaimed(jobId, "scheduler-node-1")
        );

        assertEquals(JobStatus.RUNNING, ex.getCurrentStatus());
        assertEquals(JobStatus.CLAIMED, ex.getTargetStatus());
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("markRunning - Success: CLAIMED -> RUNNING")
    void markRunning_LegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.CLAIMED);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));
        when(jobRepository.save(any(JobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JobEntity result = jobStateService.markRunning(jobId);

        assertNotNull(result);
        assertEquals(JobStatus.RUNNING, result.getStatus());
        verify(jobRepository).save(entity);
    }

    @Test
    @DisplayName("markRunning - IllegalTransition: PENDING -> RUNNING throws InvalidStateTransitionException")
    void markRunning_IllegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.PENDING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));

        InvalidStateTransitionException ex = assertThrows(
                InvalidStateTransitionException.class,
                () -> jobStateService.markRunning(jobId)
        );

        assertEquals(JobStatus.PENDING, ex.getCurrentStatus());
        assertEquals(JobStatus.RUNNING, ex.getTargetStatus());
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("markSucceeded - Success: RUNNING -> SUCCEEDED")
    void markSucceeded_LegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.RUNNING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));
        when(jobRepository.save(any(JobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JobEntity result = jobStateService.markSucceeded(jobId);

        assertNotNull(result);
        assertEquals(JobStatus.SUCCEEDED, result.getStatus());
        verify(jobRepository).save(entity);
    }

    @Test
    @DisplayName("markSucceeded - IllegalTransition: CLAIMED -> SUCCEEDED throws InvalidStateTransitionException")
    void markSucceeded_IllegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.CLAIMED);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));

        InvalidStateTransitionException ex = assertThrows(
                InvalidStateTransitionException.class,
                () -> jobStateService.markSucceeded(jobId)
        );

        assertEquals(JobStatus.CLAIMED, ex.getCurrentStatus());
        assertEquals(JobStatus.SUCCEEDED, ex.getTargetStatus());
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("markFailed - Success: RUNNING -> FAILED")
    void markFailed_LegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.RUNNING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));
        when(jobRepository.save(any(JobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JobEntity result = jobStateService.markFailed(jobId, "NullPointerException in worker");

        assertNotNull(result);
        assertEquals(JobStatus.FAILED, result.getStatus());
        assertEquals("NullPointerException in worker", result.getLastError());
        verify(jobRepository).save(entity);
    }

    @Test
    @DisplayName("markFailed - IllegalTransition: PENDING -> FAILED throws InvalidStateTransitionException")
    void markFailed_IllegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.PENDING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));

        InvalidStateTransitionException ex = assertThrows(
                InvalidStateTransitionException.class,
                () -> jobStateService.markFailed(jobId, "Error")
        );

        assertEquals(JobStatus.PENDING, ex.getCurrentStatus());
        assertEquals(JobStatus.FAILED, ex.getTargetStatus());
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("markPending - Success: FAILED -> PENDING with backoff scheduledAt")
    void markPending_FromFailed_LegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.FAILED);
        Instant nextRun = Instant.now().plusSeconds(60);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));
        when(jobRepository.save(any(JobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JobEntity result = jobStateService.markPending(jobId, nextRun);

        assertNotNull(result);
        assertEquals(JobStatus.PENDING, result.getStatus());
        assertEquals(nextRun, result.getScheduledAt());
        verify(jobRepository).save(entity);
    }

    @Test
    @DisplayName("markPending - Success: DEAD_LETTERED -> PENDING for replay")
    void markPending_FromDeadLettered_LegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.DEAD_LETTERED);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));
        when(jobRepository.save(any(JobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JobEntity result = jobStateService.markPending(jobId, null);

        assertNotNull(result);
        assertEquals(JobStatus.PENDING, result.getStatus());
        verify(jobRepository).save(entity);
    }

    @Test
    @DisplayName("markPending - IllegalTransition: RUNNING -> PENDING throws InvalidStateTransitionException")
    void markPending_IllegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.RUNNING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));

        InvalidStateTransitionException ex = assertThrows(
                InvalidStateTransitionException.class,
                () -> jobStateService.markPending(jobId, Instant.now())
        );

        assertEquals(JobStatus.RUNNING, ex.getCurrentStatus());
        assertEquals(JobStatus.PENDING, ex.getTargetStatus());
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("markDeadLettered - Success: FAILED -> DEAD_LETTERED")
    void markDeadLettered_FromFailed_LegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.FAILED);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));
        when(jobRepository.save(any(JobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JobEntity result = jobStateService.markDeadLettered(jobId, "Max retries reached");

        assertNotNull(result);
        assertEquals(JobStatus.DEAD_LETTERED, result.getStatus());
        assertEquals("Max retries reached", result.getLastError());
        verify(jobRepository).save(entity);
    }

    @Test
    @DisplayName("markDeadLettered - Success: RUNNING -> DEAD_LETTERED on fatal unrecoverable error")
    void markDeadLettered_FromRunning_LegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.RUNNING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));
        when(jobRepository.save(any(JobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JobEntity result = jobStateService.markDeadLettered(jobId, "Fatal payload corruption");

        assertNotNull(result);
        assertEquals(JobStatus.DEAD_LETTERED, result.getStatus());
        assertEquals("Fatal payload corruption", result.getLastError());
        verify(jobRepository).save(entity);
    }

    @Test
    @DisplayName("markDeadLettered - IllegalTransition: SUCCEEDED -> DEAD_LETTERED throws InvalidStateTransitionException")
    void markDeadLettered_IllegalTransition() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = createJobEntity(jobId, JobStatus.SUCCEEDED);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));

        InvalidStateTransitionException ex = assertThrows(
                InvalidStateTransitionException.class,
                () -> jobStateService.markDeadLettered(jobId, "Fatal")
        );

        assertEquals(JobStatus.SUCCEEDED, ex.getCurrentStatus());
        assertEquals(JobStatus.DEAD_LETTERED, ex.getTargetStatus());
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Job state operations throw JobNotFoundException when job ID is missing")
    void stateOperations_JobNotFound() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class, () -> jobStateService.markClaimed(jobId, "node-1"));
        assertThrows(JobNotFoundException.class, () -> jobStateService.markRunning(jobId));
        assertThrows(JobNotFoundException.class, () -> jobStateService.markSucceeded(jobId));
        assertThrows(JobNotFoundException.class, () -> jobStateService.markFailed(jobId, "err"));
        assertThrows(JobNotFoundException.class, () -> jobStateService.markPending(jobId, null));
        assertThrows(JobNotFoundException.class, () -> jobStateService.markDeadLettered(jobId, "err"));
    }

    private JobEntity createJobEntity(UUID id, JobStatus status) {
        return JobEntity.builder()
                .id(id)
                .jobType("SEND_EMAIL")
                .payload("{\"recipient\":\"user@example.com\"}")
                .status(status)
                .scheduledAt(Instant.now())
                .retryCount(0)
                .maxRetries(5)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
