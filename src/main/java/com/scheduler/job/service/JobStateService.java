package com.scheduler.job.service;

import com.scheduler.job.entity.JobEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * Centralized service responsible for managing all job state transitions.
 * Ensures that only legal transitions are permitted across the job lifecycle.
 */
public interface JobStateService {

    /**
     * Transition job status from PENDING to CLAIMED.
     *
     * @param jobId     UUID of the job to claim
     * @param claimedBy identifier of the scheduler instance claiming the job
     * @return updated JobEntity
     */
    JobEntity markClaimed(UUID jobId, String claimedBy);

    /**
     * Transition job status from CLAIMED to RUNNING.
     *
     * @param jobId UUID of the job being processed
     * @return updated JobEntity
     */
    JobEntity markRunning(UUID jobId);

    /**
     * Transition job status from RUNNING to SUCCEEDED.
     *
     * @param jobId UUID of the successfully executed job
     * @return updated JobEntity
     */
    JobEntity markSucceeded(UUID jobId);

    /**
     * Transition job status from RUNNING to FAILED.
     *
     * @param jobId     UUID of the failed job
     * @param lastError error description or stacktrace summary
     * @return updated JobEntity
     */
    JobEntity markFailed(UUID jobId, String lastError);

    /**
     * Transition job status from FAILED or DEAD_LETTERED to PENDING (for retry or replay).
     *
     * @param jobId       UUID of the job to requeue
     * @param scheduledAt next scheduled execution time (if null, existing scheduledAt is retained)
     * @return updated JobEntity
     */
    JobEntity markPending(UUID jobId, Instant scheduledAt);

    /**
     * Transition job status from FAILED or RUNNING to DEAD_LETTERED.
     *
     * @param jobId     UUID of the unrecoverable job
     * @param lastError final error message
     * @return updated JobEntity
     */
    JobEntity markDeadLettered(UUID jobId, String lastError);
}
