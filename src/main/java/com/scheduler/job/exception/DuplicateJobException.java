package com.scheduler.job.exception;

/**
 * Thrown when a job creation request contains an idempotency key that already exists.
 */
public class DuplicateJobException extends RuntimeException {

    public DuplicateJobException(String idempotencyKey) {
        super(String.format("Job with idempotency key '%s' already exists", idempotencyKey));
    }
}
