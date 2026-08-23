package com.scheduler.job.queue;

import com.scheduler.job.entity.JobEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fallback implementation of {@link QueuePublisher} used when no external AWS SQS producer is configured.
 */
@Component
public class NoopQueuePublisher implements QueuePublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopQueuePublisher.class);

    @Override
    public void publish(JobEntity job) {
        log.info("NOOP queue publisher: pretend publishing job '{}' (type: {}) to queue",
                job.getId(), job.getJobType());
    }

    @Override
    public void publishDLQ(JobEntity job) {
        log.info("NOOP queue publisher: pretend publishing job '{}' (type: {}) to DLQ",
                job.getId(), job.getJobType());
    }
}
