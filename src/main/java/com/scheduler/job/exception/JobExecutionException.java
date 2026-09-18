package com.scheduler.job.exception;

/**
 * Domain exception thrown when a {@code JobHandler} fails to execute a job.
 *
 * <p>Signals a failure of the business work itself, whether the payload was unusable or the work could
 * not be completed. The worker turns this into an intentional state transition; it is never swallowed.
 */
public class JobExecutionException extends RuntimeException {

    public JobExecutionException(String message) {
        super(message);
    }

    public JobExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
