package com.scheduler.job.exception;

/**
 * Domain exception thrown when no {@code JobHandler} is registered for a job type.
 *
 * <p>Distinct from {@link JobExecutionException}: the work never started, because nothing knows how to
 * perform it. Retrying cannot help, since the set of handlers does not change while the process runs.
 */
public class HandlerNotFoundException extends RuntimeException {

    public HandlerNotFoundException(String message) {
        super(message);
    }
}
