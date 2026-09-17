package com.scheduler.job.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.job.config.AwsProperties;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.exception.QueuePublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.Objects;
import java.util.UUID;

/**
 * AWS SQS implementation of {@link QueuePublisher}.
 *
 * <p>Publishes a {@link JobMessage} — never the JPA entity — to the main execution queue or the DLQ.
 *
 * <p><strong>Failure semantics.</strong> Every failure path throws {@link QueuePublishException}.
 * {@code JobClaimService} publishes inside its transaction, so the throw marks that transaction for
 * rollback and the job reverts from {@code CLAIMED} to {@code PENDING}, to be rediscovered on a later poll.
 *
 * <p><strong>What this class cannot guarantee.</strong> PostgreSQL and SQS are separate systems with no
 * shared transaction:
 * <ul>
 *   <li>SQS accepts the message and the DB transaction then rolls back: the job row is {@code PENDING},
 *       so the worker's {@code CLAIMED -> RUNNING} transition rejects the stale message and it is discarded.</li>
 *   <li>The DB commits {@code CLAIMED} but the process dies before {@code sendMessage} is acknowledged:
 *       the row stays {@code CLAIMED} with no executable message. Phase 2 has no stale-claim sweeper;
 *       recovery of such rows is Phase 3 work and is intentionally absent here.</li>
 *   <li>{@code sendMessage} times out after SQS actually accepted the message: a retry produces a
 *       duplicate delivery. Delivery is at-least-once; duplicates are made safe by the state machine,
 *       not by the queue.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "aws.sqs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SqsQueuePublisher implements QueuePublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsQueuePublisher.class);

    private static final String MAIN_QUEUE = "main";
    private static final String DEAD_LETTER_QUEUE = "dlq";

    private final SqsClient sqsClient;
    private final SqsQueueUrlProvider queueUrlProvider;
    private final ObjectMapper objectMapper;
    private final AwsProperties awsProperties;

    public SqsQueuePublisher(SqsClient sqsClient,
                             SqsQueueUrlProvider queueUrlProvider,
                             ObjectMapper objectMapper,
                             AwsProperties awsProperties) {
        this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient must not be null");
        this.queueUrlProvider = Objects.requireNonNull(queueUrlProvider, "queueUrlProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.awsProperties = Objects.requireNonNull(awsProperties, "awsProperties must not be null");
    }

    @Override
    public void publish(JobEntity job) {
        send(job, MAIN_QUEUE, awsProperties.sqs().queueName(), queueUrlProvider::getQueueUrl);
    }

    @Override
    public void publishDLQ(JobEntity job) {
        send(job, DEAD_LETTER_QUEUE, awsProperties.sqs().dlqName(), queueUrlProvider::getDlqUrl);
    }

    private void send(JobEntity job, String target, String queueName, QueueUrlSupplier queueUrlSupplier) {
        if (job == null || job.getId() == null) {
            throw new QueuePublishException(
                    "Cannot publish to " + target + " queue: job or job id is null");
        }

        String traceId = UUID.randomUUID().toString();
        JobMessage message = buildMessage(job, traceId, target);
        String body = serialize(message, target);

        try {
            String queueUrl = queueUrlSupplier.get();
            SendMessageResponse response = sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());

            log.info("Published job to {} queue: job_id={}, job_type={}, retry_count={}, trace_id={}, "
                            + "queue={}, sqs_message_id={}",
                    target, job.getId(), job.getJobType(), job.getRetryCount(), traceId, queueName,
                    response.messageId());
        } catch (SdkException e) {
            log.error("Failed to publish job to {} queue: job_id={}, job_type={}, trace_id={}, queue={}",
                    target, job.getId(), job.getJobType(), traceId, queueName, e);
            throw new QueuePublishException(String.format(
                    "Failed to publish job '%s' to %s queue '%s'", job.getId(), target, queueName), e);
        }
    }

    private JobMessage buildMessage(JobEntity job, String traceId, String target) {
        try {
            return JobMessage.from(job, traceId);
        } catch (RuntimeException e) {
            log.error("Job '{}' cannot be represented as a valid queue message for the {} queue",
                    job.getId(), target, e);
            throw new QueuePublishException(String.format(
                    "Job '%s' cannot be represented as a valid queue message", job.getId()), e);
        }
    }

    private String serialize(JobMessage message, String target) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize queue message for the {} queue: job_id={}, trace_id={}",
                    target, message.jobId(), message.traceId(), e);
            throw new QueuePublishException(String.format(
                    "Failed to serialize queue message for job '%s'", message.jobId()), e);
        }
    }

    /**
     * Supplier of a queue URL that is allowed to fail with an SDK exception, so that lazy queue-URL
     * resolution failures are wrapped into {@link QueuePublishException} on the publish path.
     */
    @FunctionalInterface
    private interface QueueUrlSupplier {
        String get();
    }
}
