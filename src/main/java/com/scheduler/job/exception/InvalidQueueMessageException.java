package com.scheduler.job.exception;

/**
 * Domain exception thrown when a message received from SQS cannot be deserialized into a valid
 * job message, or is structurally valid JSON but violates the message contract
 * (missing jobId, blank jobType, negative retryCount, ...).
 *
 * <p>Such a message is unprocessable no matter how often it is redelivered.
 */
public class InvalidQueueMessageException extends RuntimeException {

    public InvalidQueueMessageException(String message) {
        super(message);
    }

    public InvalidQueueMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
