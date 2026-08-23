package com.scheduler.job.queue;

import com.scheduler.job.entity.JobEntity;

/**
 * Interface abstraction for SQS queue publication operations.
 */
public interface QueuePublisher {

    /**
     * Serializes and publishes a claimed job to the main SQS processing queue.
     *
     * @param job JobEntity record to publish
     */
    void publish(JobEntity job);

    /**
     * Serializes and publishes a dead-lettered job to the SQS Dead Letter Queue (DLQ).
     *
     * @param job JobEntity record to publish to DLQ
     */
    void publishDLQ(JobEntity job);
}
