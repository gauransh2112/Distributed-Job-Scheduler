package com.scheduler.job.exception;

/**
 * Domain exception thrown when publishing a job message to SQS queue fails.
 */
public class QueuePublishException extends RuntimeException {

    public QueuePublishException(String message) {
        super(message);
    }

    public QueuePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
