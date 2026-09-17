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
 * the {@link JobMessage} contract, can never become processable — redelivering it would block the queue
 * with a poison message. Such a message is therefore logged at ERROR with its identity and stack trace
 * and then deleted. Nothing is lost silently: the job row itself remains in PostgreSQL as the source of
 * truth, and the failure is visible in the logs.
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
        String queueUrl;
        List<Message> messages;
        try {
            queueUrl = queueUrlProvider.getQueueUrl();
            messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(sqs.maxMessagesPerPoll())
                    .waitTimeSeconds(sqs.waitTimeSeconds())
                    .visibilityTimeout(sqs.visibilityTimeoutSeconds())
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
                discardPoisonMessage(queueUrl, message, e);
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
     * Deletes a structurally unprocessable message so it cannot be redelivered indefinitely.
     *
     * <p>The deletion failure is logged rather than rethrown: the caller is mid-batch, the message is
     * already unusable, and failing the whole receive would also drop the valid messages in the batch.
     * If the delete fails the message simply becomes visible again and is discarded again on redelivery.
     */
    private void discardPoisonMessage(String queueUrl, Message message, InvalidQueueMessageException cause) {
        log.error("Discarding unprocessable SQS message: sqs_message_id={}, queue={}, body_length={}",
                message.messageId(), awsProperties.sqs().queueName(),
                message.body() == null ? 0 : message.body().length(), cause);
        try {
            deleteMessage(queueUrl, message.receiptHandle());
        } catch (SdkException e) {
            log.error("Failed to delete unprocessable SQS message '{}'; it will be discarded again on redelivery",
                    message.messageId(), e);
        }
    }

    private void deleteMessage(String queueUrl, String receiptHandle) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }
}
