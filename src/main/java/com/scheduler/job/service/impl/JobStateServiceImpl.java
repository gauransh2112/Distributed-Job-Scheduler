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
 * <p><strong>Concurrency design.</strong> Most methods here read with {@code findById()}, check the status in
 * memory and write. That is a read-modify-write and provides no concurrency control on its own: {@code findById()}
 * issues a plain SELECT with no row lock, and the entity carries no {@code @Version} column. It is safe only where
 * a single caller is already established as the owner of the transition.</p>
 *
 * <p><strong>{@code markRunning} is the exception, and the reason matters.</strong> It is the execution-ownership
 * gate: SQS delivers at least once, so two workers can hold the same message simultaneously, and whichever wins this
 * transition is the one that runs the handler. A read-modify-write cannot decide that, because both workers read
 * {@code CLAIMED}, both pass the check and both write {@code RUNNING}. It is therefore implemented as a single
 * conditional UPDATE ({@link JobRepository#markRunningIfClaimed}). Under PostgreSQL's default READ COMMITTED
 * behaviour the losing transaction re-evaluates the condition against the already-updated row, so exactly one
 * caller sees one updated row and every other caller sees zero.</p>
 *
 * <p>Task 2's {@code FOR UPDATE SKIP LOCKED} does not cover this: it guards {@code PENDING -> CLAIMED} during
 * scheduler claiming. An SQS visibility timeout narrows the duplicate-delivery window but cannot close it, so it is
 * not a correctness mechanism either.</p>
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

    /**
     * {@inheritDoc}
     *
     * <p>Implemented as an atomic compare-and-swap so that exactly one of any number of concurrent
     * callers wins. A caller that loses is rejected with {@link InvalidStateTransitionException} and
     * must not execute the job.
     */
    @Override
    @Transactional
    public JobEntity markRunning(UUID jobId) {
        int updatedRows = jobRepository.markRunningIfClaimed(jobId, Instant.now());

        if (updatedRows == 0) {
            // Either the job does not exist, or it was not CLAIMED: another worker already took
            // ownership, or the row never legitimately reached CLAIMED. Re-read to report which.
            JobEntity job = getJobOrThrow(jobId);
            log.info("Job '{}' rejected CLAIMED -> RUNNING: current status is {}", jobId, job.getStatus());
            throw new InvalidStateTransitionException(jobId, job.getStatus(), JobStatus.RUNNING);
        }

        log.info("Job '{}' state transitioned: CLAIMED -> RUNNING", jobId);
        return getJobOrThrow(jobId);
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
