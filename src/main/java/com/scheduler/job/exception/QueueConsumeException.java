package com.scheduler.job.exception;

/**
 * Domain exception thrown when receiving from or acknowledging against the SQS queue fails.
 *
 * <p>Distinct from {@link QueuePublishException}: this represents a failure of the consumption side
 * (receive / delete), not of publication.
 */
public class QueueConsumeException extends RuntimeException {

    public QueueConsumeException(String message) {
        super(message);
    }

    public QueueConsumeException(String message, Throwable cause) {
        super(message, cause);
    }
}
