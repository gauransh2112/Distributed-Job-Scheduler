package com.scheduler.job.queue;

import com.scheduler.job.entity.JobEntity;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op {@link QueuePublisher} that discards messages instead of publishing them.
 *
 * <p>Only activated when SQS is explicitly disabled via {@code aws.sqs.enabled=false}; the SQS publisher
 * is the default whenever that property is absent. The two implementations are mutually exclusive, so
 * this one can never be selected by accident, and it announces itself loudly when it is.
 *
 * <p>Jobs published here are claimed in PostgreSQL but never executed: the row stays {@code CLAIMED}
 * forever. This exists for local wiring experiments only and must not be enabled in a deployed environment.
 */
@Component
@ConditionalOnProperty(prefix = "aws.sqs", name = "enabled", havingValue = "false")
public class NoopQueuePublisher implements QueuePublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopQueuePublisher.class);

    @PostConstruct
    void warnAboutNoopPublisher() {
        log.warn("aws.sqs.enabled=false: NoopQueuePublisher is active. Claimed jobs are NOT published to SQS "
                + "and will never be executed. Do not use this configuration outside local experiments.");
    }

    @Override
    public void publish(JobEntity job) {
        log.warn("NOOP queue publisher: discarding job '{}' (type: {}) instead of publishing to the execution queue",
                job.getId(), job.getJobType());
    }

    @Override
    public void publishDLQ(JobEntity job) {
        log.warn("NOOP queue publisher: discarding job '{}' (type: {}) instead of publishing to the DLQ",
                job.getId(), job.getJobType());
    }
}
