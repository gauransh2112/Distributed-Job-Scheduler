package com.scheduler.job.service.impl;

import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.InvalidStateTransitionException;
import com.scheduler.job.exception.JobNotFoundException;
import com.scheduler.job.repository.JobRepository;
import com.scheduler.job.service.JobStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Production implementation of {@link JobStateService}.
 * Centralizes state transitions, enforces strict state validation preconditions, and logs all updates.
 *
 * <p><strong>Concurrency Design Note:</strong> Standard {@code findById()} calls within these {@code @Transactional}
 * methods perform plain SELECTs and do not acquire row locks. Single-worker execution ownership is guaranteed upstream
 * by the atomic claiming mechanism (Task 2: PostgreSQL {@code FOR UPDATE SKIP LOCKED}), SQS visibility timeouts, and
 * the single-use {@code CLAIMED -> RUNNING} transition gate, preventing concurrent worker execution races.</p>
 */
@Service
public class JobStateServiceImpl implements JobStateService {

    private static final Logger log = LoggerFactory.getLogger(JobStateServiceImpl.class);

    private final JobRepository jobRepository;

    public JobStateServiceImpl(JobRepository jobRepository) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
    }

    @Override
    @Transactional
    public JobEntity markClaimed(UUID jobId, String claimedBy) {
        JobEntity job = getJobOrThrow(jobId);
        validateTransition(job, JobStatus.PENDING, JobStatus.CLAIMED);

        job.setStatus(JobStatus.CLAIMED);
        job.setClaimedBy(claimedBy);

        log.info("Job '{}' state transitioned: PENDING -> CLAIMED by instance '{}'", jobId, claimedBy);
        return jobRepository.save(job);
    }

    @Override
    @Transactional
    public JobEntity markRunning(UUID jobId) {
        JobEntity job = getJobOrThrow(jobId);
        validateTransition(job, JobStatus.CLAIMED, JobStatus.RUNNING);

        job.setStatus(JobStatus.RUNNING);

        log.info("Job '{}' state transitioned: CLAIMED -> RUNNING", jobId);
        return jobRepository.save(job);
    }

    @Override
    @Transactional
    public JobEntity markSucceeded(UUID jobId) {
        JobEntity job = getJobOrThrow(jobId);
        validateTransition(job, JobStatus.RUNNING, JobStatus.SUCCEEDED);

        job.setStatus(JobStatus.SUCCEEDED);

        log.info("Job '{}' state transitioned: RUNNING -> SUCCEEDED", jobId);
        return jobRepository.save(job);
    }

    @Override
    @Transactional
    public JobEntity markFailed(UUID jobId, String lastError) {
        JobEntity job = getJobOrThrow(jobId);
        validateTransition(job, JobStatus.RUNNING, JobStatus.FAILED);

        job.setStatus(JobStatus.FAILED);
        job.setLastError(lastError);

        log.info("Job '{}' state transitioned: RUNNING -> FAILED. Error: {}", jobId, lastError);
        return jobRepository.save(job);
    }

    @Override
    @Transactional
    public JobEntity markPending(UUID jobId, Instant scheduledAt) {
        JobEntity job = getJobOrThrow(jobId);
        JobStatus currentStatus = job.getStatus();

        if (currentStatus != JobStatus.FAILED && currentStatus != JobStatus.DEAD_LETTERED) {
            throw new InvalidStateTransitionException(jobId, currentStatus, JobStatus.PENDING);
        }

        job.setStatus(JobStatus.PENDING);
        if (scheduledAt != null) {
            job.setScheduledAt(scheduledAt);
        }

        log.info("Job '{}' state transitioned: {} -> PENDING (scheduledAt: {})", jobId, currentStatus, job.getScheduledAt());
        return jobRepository.save(job);
    }

    @Override
    @Transactional
    public JobEntity markDeadLettered(UUID jobId, String lastError) {
        JobEntity job = getJobOrThrow(jobId);
        JobStatus currentStatus = job.getStatus();

        if (currentStatus != JobStatus.FAILED && currentStatus != JobStatus.RUNNING) {
            throw new InvalidStateTransitionException(jobId, currentStatus, JobStatus.DEAD_LETTERED);
        }

        job.setStatus(JobStatus.DEAD_LETTERED);
        if (lastError != null) {
            job.setLastError(lastError);
        }

        log.info("Job '{}' state transitioned: {} -> DEAD_LETTERED. Final Error: {}", jobId, currentStatus, lastError);
        return jobRepository.save(job);
    }

    private JobEntity getJobOrThrow(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }

    private void validateTransition(JobEntity job, JobStatus expectedCurrentStatus, JobStatus targetStatus) {
        if (job.getStatus() != expectedCurrentStatus) {
            throw new InvalidStateTransitionException(job.getId(), job.getStatus(), targetStatus);
        }
    }
}
