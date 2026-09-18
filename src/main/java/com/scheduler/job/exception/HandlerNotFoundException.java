package com.scheduler.job.exception;

/**
 * Domain exception thrown when no {@code JobHandler} is registered for a job type.
 *
 * <p>Distinct from {@link JobExecutionException}: that means a handler ran and the work failed, whereas
 * this means no handler exists and the work never started.
 *
 * <p>How this failure is classified for retry or dead lettering is not decided here; that belongs to
 * the retry policy.
 */
public class HandlerNotFoundException extends RuntimeException {

    public HandlerNotFoundException(String message) {
        super(message);
    }
}
