package com.scheduler.job.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.job.config.AwsProperties;
import com.scheduler.job.config.SqsConfiguration;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.QueuePublishException;
import com.scheduler.job.queue.JobMessage;
import com.scheduler.job.queue.QueueConsumer;
import com.scheduler.job.queue.QueuePublisher;
import com.scheduler.job.queue.ReceivedJobMessage;
import com.scheduler.job.queue.SqsQueueConsumer;
import com.scheduler.job.queue.SqsQueuePublisher;
import com.scheduler.job.queue.SqsQueueUrlProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the SQS queue infrastructure against a real LocalStack SQS service.
 *
 * <p>Nothing about the AWS client is mocked: the client is built by the production
 * {@link SqsConfiguration} bean method, and the production {@link SqsQueuePublisher} and
 * {@link SqsQueueConsumer} are exercised against a real queue. Mocking the SDK here would only prove
 * that the mock was configured as expected — it would say nothing about serialization, queue
 * resolution, visibility timeouts or redelivery, which is exactly what these tests are for.
 *
 * <p>Each test uses freshly named queues so that in-flight messages from one test cannot leak into another.
 */
@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
class SqsQueueIntegrationTest {

    private static final int VISIBILITY_TIMEOUT_SECONDS = 2;
    private static final int WAIT_TIME_SECONDS = 1;
    private static final int MAX_MESSAGES_PER_POLL = 10;

    @Container
    static final LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.4.0"))
            .withServices(LocalStackContainer.Service.SQS);

    private static ObjectMapper objectMapper;
    private static SqsClient sqsClient;

    @BeforeAll
    static void startClient() {
        objectMapper = new ObjectMapper();
        // Built through the production configuration class, so the endpoint override and static
        // credential handling are covered by these tests rather than reimplemented in test code.
        sqsClient = new SqsConfiguration().sqsClient(properties("bootstrap-queue", "bootstrap-dlq", true));
    }

    @AfterAll
    static void closeClient() {
        if (sqsClient != null) {
            sqsClient.close();
        }
    }

    @Test
    @DisplayName("publish then consume round-trips every message contract field through real SQS")
    void testPublishAndConsumeRoundTrip() {
        Fixture fixture = newFixture(true);
        JobEntity job = job("SEND_EMAIL", 2);

        fixture.publisher.publish(job);

        List<ReceivedJobMessage> received = consumeAtLeast(fixture.consumer, 1, Duration.ofSeconds(20));
        assertEquals(1, received.size());

        JobMessage message = received.get(0).message();
        assertEquals(job.getId(), message.jobId());
        assertEquals("SEND_EMAIL", message.jobType());
        assertEquals(job.getPayload(), message.payload());
        assertEquals(2, message.retryCount());
        assertNotNull(message.traceId(), "Publisher must attach a traceId for cross-component correlation");
        assertFalse(message.traceId().isBlank());
        assertNotNull(received.get(0).receiptHandle());
        assertNotNull(received.get(0).messageId());
    }

    @Test
    @DisplayName("publishDLQ delivers to the dead letter queue and not to the main execution queue")
    void testPublishToDlqDoesNotReachMainQueue() {
        Fixture fixture = newFixture(true);
        JobEntity job = job("CLEANUP", 5);

        fixture.publisher.publishDLQ(job);

        // The main queue consumer must see nothing.
        assertNoMessages(fixture.consumer, Duration.ofSeconds(5));

        // The DLQ itself holds the message, with retry count and identity preserved.
        QueueConsumer dlqConsumer = consumerFor(fixture.dlqName, fixture.dlqName);
        List<ReceivedJobMessage> dlqMessages = consumeAtLeast(dlqConsumer, 1, Duration.ofSeconds(20));
        assertEquals(1, dlqMessages.size());
        assertEquals(job.getId(), dlqMessages.get(0).message().jobId());
        assertEquals(5, dlqMessages.get(0).message().retryCount());
    }

    @Test
    @DisplayName("A message left unacknowledged stays invisible and is redelivered after the visibility timeout")
    void testUnacknowledgedMessageIsRedeliveredAfterVisibilityTimeout() {
        Fixture fixture = newFixture(true);
        JobEntity job = job("SEND_EMAIL", 0);
        fixture.publisher.publish(job);

        List<ReceivedJobMessage> first = consumeAtLeast(fixture.consumer, 1, Duration.ofSeconds(20));
        assertEquals(1, first.size());

        // Still in flight: another consumer poll must not see it during the visibility window.
        assertTrue(fixture.consumer.consume().isEmpty(),
                "An in-flight message must not be visible to a second consumer");

        // Not acknowledged: SQS makes it visible again. This redelivery is the crash-recovery path,
        // and the reason delivery is at-least-once rather than exactly-once.
        List<ReceivedJobMessage> redelivered = consumeAtLeast(fixture.consumer, 1, Duration.ofSeconds(20));
        assertEquals(1, redelivered.size());
        assertEquals(job.getId(), redelivered.get(0).message().jobId());
    }

    @Test
    @DisplayName("An acknowledged message is deleted and never redelivered")
    void testAcknowledgedMessageIsNotRedelivered() {
        Fixture fixture = newFixture(true);
        JobEntity job = job("GENERATE_REPORT", 0);
        fixture.publisher.publish(job);

        List<ReceivedJobMessage> received = consumeAtLeast(fixture.consumer, 1, Duration.ofSeconds(20));
        assertEquals(1, received.size());

        fixture.consumer.acknowledge(received.get(0));

        assertNoMessages(fixture.consumer, Duration.ofSeconds(VISIBILITY_TIMEOUT_SECONDS * 3L));
    }

    @Test
    @DisplayName("A malformed message body is discarded safely and does not block valid messages")
    void testMalformedMessageIsDiscardedAndValidMessagesStillFlow() {
        Fixture fixture = newFixture(true);
        String queueUrl = queueUrl(fixture.queueName);
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody("this is not json")
                .build());

        JobEntity job = job("SEND_EMAIL", 0);
        fixture.publisher.publish(job);

        List<ReceivedJobMessage> received = consumeAtLeast(fixture.consumer, 1, Duration.ofSeconds(20));
        assertEquals(1, received.size(), "Only the valid message may be returned to the caller");
        assertEquals(job.getId(), received.get(0).message().jobId());
        fixture.consumer.acknowledge(received.get(0));

        // The poison message was deleted rather than left to be redelivered forever.
        assertNoMessages(fixture.consumer, Duration.ofSeconds(VISIBILITY_TIMEOUT_SECONDS * 3L));
    }

    @Test
    @DisplayName("A structurally valid JSON message that violates the message contract is discarded")
    void testContractViolatingMessageIsDiscarded() {
        Fixture fixture = newFixture(true);
        // Valid JSON, but jobType is missing: unprocessable no matter how often it is redelivered.
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl(fixture.queueName))
                .messageBody("{\"jobId\":\"" + UUID.randomUUID()
                        + "\",\"payload\":\"{}\",\"retryCount\":0,\"traceId\":\"trace-contract\"}")
                .build());

        assertNoMessages(fixture.consumer, Duration.ofSeconds(VISIBILITY_TIMEOUT_SECONDS * 3L));
    }

    @Test
    @DisplayName("Publishing to a queue that does not exist fails with QueuePublishException when auto-creation is off")
    void testPublishToMissingQueueThrowsQueuePublishException() {
        Fixture fixture = newFixture(false);

        QueuePublishException thrown = assertThrows(QueuePublishException.class,
                () -> fixture.publisher.publish(job("SEND_EMAIL", 0)));

        assertTrue(thrown.getMessage().contains("Failed to publish job"));
        assertNotNull(thrown.getCause(), "The underlying SDK failure must be preserved, not swallowed");
    }

    @Test
    @DisplayName("Publishing a job without an id fails fast instead of putting an unusable message on the queue")
    void testPublishRejectsJobWithoutId() {
        Fixture fixture = newFixture(true);
        JobEntity job = job("SEND_EMAIL", 0);
        job.setId(null);

        QueuePublishException thrown = assertThrows(QueuePublishException.class,
                () -> fixture.publisher.publish(job));
        assertTrue(thrown.getMessage().contains("job or job id is null"));
        assertNoMessages(fixture.consumer, Duration.ofSeconds(3));
    }

    // ---------------------------------------------------------------------
    // Fixtures and helpers
    // ---------------------------------------------------------------------

    private record Fixture(String queueName, String dlqName, QueuePublisher publisher, QueueConsumer consumer) {
    }

    private Fixture newFixture(boolean autoCreateQueues) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String queueName = "jobs-" + suffix;
        String dlqName = "jobs-dlq-" + suffix;
        AwsProperties properties = properties(queueName, dlqName, autoCreateQueues);
        SqsQueueUrlProvider urlProvider = new SqsQueueUrlProvider(sqsClient, properties);
        return new Fixture(
                queueName,
                dlqName,
                new SqsQueuePublisher(sqsClient, urlProvider, objectMapper, properties),
                new SqsQueueConsumer(sqsClient, urlProvider, objectMapper, properties));
    }

    private QueueConsumer consumerFor(String queueName, String dlqName) {
        AwsProperties properties = properties(queueName, dlqName, true);
        return new SqsQueueConsumer(sqsClient, new SqsQueueUrlProvider(sqsClient, properties),
                objectMapper, properties);
    }

    private String queueUrl(String queueName) {
        AwsProperties properties = properties(queueName, queueName, true);
        return new SqsQueueUrlProvider(sqsClient, properties).getQueueUrl();
    }

    private static AwsProperties properties(String queueName, String dlqName, boolean autoCreateQueues) {
        return new AwsProperties(
                localstack.getRegion(),
                localstack.getAccessKey(),
                localstack.getSecretKey(),
                new AwsProperties.Sqs(
                        localstack.getEndpoint().toString(),
                        queueName,
                        dlqName,
                        VISIBILITY_TIMEOUT_SECONDS,
                        WAIT_TIME_SECONDS,
                        MAX_MESSAGES_PER_POLL,
                        autoCreateQueues));
    }

    /**
     * Polls until the expected number of messages has been received or the deadline expires.
     * SQS receive calls may return a subset of the available messages, so a single call is not a
     * reliable assertion target.
     */
    private List<ReceivedJobMessage> consumeAtLeast(QueueConsumer consumer, int expected, Duration timeout) {
        List<ReceivedJobMessage> accumulated = new ArrayList<>();
        Instant deadline = Instant.now().plus(timeout);
        while (accumulated.size() < expected && Instant.now().isBefore(deadline)) {
            accumulated.addAll(consumer.consume());
        }
        assertTrue(accumulated.size() >= expected,
                "Expected at least " + expected + " message(s) within " + timeout + ", got " + accumulated.size());
        return accumulated;
    }

    /**
     * Asserts that no message becomes available for the given duration. The duration must exceed the
     * visibility timeout for redelivery assertions to be meaningful.
     */
    private void assertNoMessages(QueueConsumer consumer, Duration within) {
        Instant deadline = Instant.now().plus(within);
        while (Instant.now().isBefore(deadline)) {
            List<ReceivedJobMessage> messages = consumer.consume();
            assertTrue(messages.isEmpty(), "Expected no messages, but received " + messages.size());
        }
    }

    private JobEntity job(String jobType, int retryCount) {
        return JobEntity.builder()
                .id(UUID.randomUUID())
                .jobType(jobType)
                .payload("{\"to\":\"user@example.com\",\"subject\":\"hello\"}")
                .status(JobStatus.CLAIMED)
                .scheduledAt(Instant.now())
                .retryCount(retryCount)
                .maxRetries(5)
                .claimedBy("scheduler-test-1")
                .build();
    }
}
