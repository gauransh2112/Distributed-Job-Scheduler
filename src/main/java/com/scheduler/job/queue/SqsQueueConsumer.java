package com.scheduler.job.queue;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.job.config.AwsProperties;
import com.scheduler.job.exception.InvalidQueueMessageException;
import com.scheduler.job.exception.QueueConsumeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * AWS SQS implementation of {@link QueueConsumer}.
 *
 * <p>Responsible only for the transport concerns: receive, deserialize, validate, acknowledge.
 * It knows nothing about job state, handlers or retries; dispatching a received message to the worker
 * is wired up in the worker task, which is why this class exposes receive/acknowledge as separate
 * primitives instead of driving execution itself.
 *
 * <p><strong>Visibility timeout.</strong> The timeout is applied per receive call, so the value is the
 * configured one regardless of how the queue itself was provisioned. It is the window a worker has to
 * reach a durable outcome; if the worker dies inside that window the message becomes visible again and
 * is redelivered. That redelivery is the recovery mechanism, and also the reason delivery is
 * at-least-once rather than exactly-once.
 *
 * <p><strong>Invalid message policy.</strong> A message whose body is not parseable, or which violates
 * the {@link JobMessage} contract, is logged at ERROR with its identity and stack trace and is
 * <strong>not</strong> acknowledged. It is withheld from the caller and left on the queue, so the
 * visibility timeout expires and SQS redelivers it.
 *
 * <p>Deleting such a message would be unsafe in Phase 2. The corresponding job row is {@code CLAIMED},
 * and the scheduler only rediscovers {@code PENDING} rows, so destroying the only executable copy of
 * the message would strand that job in {@code CLAIMED} forever with no execution and no recovery path.
 * Redelivery keeps the job recoverable.
 *
 * <p>The accepted cost is that an unprocessable message is redelivered indefinitely and logged on every
 * delivery. That is deliberate for Phase 2: a noisy, visible, recoverable loop is preferable to silent
 * permanent data loss. Deciding such a message's terminal fate belongs to the dead letter queue policy
 * in a later task, which owns application-level DLQ handling. {@code ApproximateReceiveCount} is logged
 * on every occurrence so a looping message is immediately visible to an operator.
 */
@Component
@ConditionalOnProperty(prefix = "aws.sqs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SqsQueueConsumer implements QueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsQueueConsumer.class);

    private final SqsClient sqsClient;
    private final SqsQueueUrlProvider queueUrlProvider;
    private final ObjectMapper objectMapper;
    private final AwsProperties awsProperties;

    public SqsQueueConsumer(SqsClient sqsClient,
                            SqsQueueUrlProvider queueUrlProvider,
                            ObjectMapper objectMapper,
                            AwsProperties awsProperties) {
        this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient must not be null");
        this.queueUrlProvider = Objects.requireNonNull(queueUrlProvider, "queueUrlProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.awsProperties = Objects.requireNonNull(awsProperties, "awsProperties must not be null");
    }

    @Override
    public List<ReceivedJobMessage> consume() {
        AwsProperties.Sqs sqs = awsProperties.sqs();
        List<Message> messages;
        try {
            String queueUrl = queueUrlProvider.getQueueUrl();
            messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(sqs.maxMessagesPerPoll())
                    .waitTimeSeconds(sqs.waitTimeSeconds())
                    .visibilityTimeout(sqs.visibilityTimeoutSeconds())
                    .attributeNamesWithStrings(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT.toString())
                    .build()).messages();
        } catch (SdkException e) {
            log.error("Failed to receive messages from SQS queue '{}'", sqs.queueName(), e);
            throw new QueueConsumeException(
                    String.format("Failed to receive messages from queue '%s'", sqs.queueName()), e);
        }

        if (messages.isEmpty()) {
            log.debug("No messages received from SQS queue '{}'", sqs.queueName());
            return List.of();
        }

        List<ReceivedJobMessage> received = new ArrayList<>(messages.size());
        for (Message message : messages) {
            try {
                JobMessage jobMessage = deserialize(message);
                log.info("Received job message: job_id={}, job_type={}, retry_count={}, trace_id={}, "
                                + "sqs_message_id={}",
                        jobMessage.jobId(), jobMessage.jobType(), jobMessage.retryCount(),
                        jobMessage.traceId(), message.messageId());
                received.add(new ReceivedJobMessage(message.messageId(), message.receiptHandle(), jobMessage));
            } catch (InvalidQueueMessageException e) {
                withholdPoisonMessage(message, e);
            }
        }
        return received;
    }

    @Override
    public void acknowledge(ReceivedJobMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        AwsProperties.Sqs sqs = awsProperties.sqs();
        try {
            deleteMessage(queueUrlProvider.getQueueUrl(), message.receiptHandle());
            log.debug("Acknowledged job message: job_id={}, trace_id={}, sqs_message_id={}",
                    message.message().jobId(), message.message().traceId(), message.messageId());
        } catch (SdkException e) {
            log.error("Failed to acknowledge message on SQS queue '{}': job_id={}, trace_id={}, sqs_message_id={}",
                    sqs.queueName(), message.message().jobId(), message.message().traceId(), message.messageId(), e);
            throw new QueueConsumeException(String.format(
                    "Failed to acknowledge message '%s' on queue '%s'", message.messageId(), sqs.queueName()), e);
        }
    }

    private JobMessage deserialize(Message message) {
        try {
            return objectMapper.readValue(message.body(), JobMessage.class);
        } catch (JacksonException e) {
            throw new InvalidQueueMessageException(String.format(
                    "Message '%s' does not satisfy the job message contract", message.messageId()), e);
        }
    }

    /**
     * Logs an unprocessable message and withholds it from the caller without acknowledging it.
     *
     * <p>The message is deliberately left on the queue: it is not deleted here, and no deletion failure
     * can be swallowed because no deletion is attempted. The visibility timeout expires and SQS
     * redelivers the message, which keeps the underlying {@code CLAIMED} job recoverable instead of
     * stranding it with its only executable message destroyed.
     */
    private void withholdPoisonMessage(Message message, InvalidQueueMessageException cause) {
        log.error("Unprocessable SQS message withheld and left on the queue for redelivery: "
                        + "sqs_message_id={}, queue={}, body_length={}, approximate_receive_count={}",
                message.messageId(), awsProperties.sqs().queueName(),
                message.body() == null ? 0 : message.body().length(),
                message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT), cause);
    }

    private void deleteMessage(String queueUrl, String receiptHandle) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }
}
