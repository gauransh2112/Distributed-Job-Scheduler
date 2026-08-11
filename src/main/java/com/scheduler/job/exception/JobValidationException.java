package com.scheduler.job.exception;

/**
 * Thrown when a job submission payload fails domain validation rules.
 */
public class JobValidationException extends RuntimeException {

    public JobValidationException(String message) {
        super(message);
    }

    public JobValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
