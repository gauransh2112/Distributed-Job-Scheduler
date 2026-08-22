package com.scheduler.job.exception;

import com.scheduler.job.entity.JobStatus;

import java.util.UUID;

/**
 * Domain exception thrown when an illegal job state transition is attempted.
 */
public class InvalidStateTransitionException extends RuntimeException {

    private final UUID jobId;
    private final JobStatus currentStatus;
    private final JobStatus targetStatus;

    public InvalidStateTransitionException(UUID jobId, JobStatus currentStatus, JobStatus targetStatus) {
        super(String.format("Invalid state transition for job '%s': cannot transition from '%s' to '%s'",
                jobId, currentStatus, targetStatus));
        this.jobId = jobId;
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public UUID getJobId() {
        return jobId;
    }

    public JobStatus getCurrentStatus() {
        return currentStatus;
    }

    public JobStatus getTargetStatus() {
        return targetStatus;
    }
}
