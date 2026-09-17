package com.scheduler.job.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.job.config.AwsProperties;
import com.scheduler.job.exception.QueueConsumeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the consumer's own control flow: which SQS calls it makes, and how it translates
 * failures.
 *
 * <p>The SQS client is mocked here on purpose, and only here. These tests assert what the consumer
 * <em>does</em> — that it never issues a delete for an unprocessable message, and that it never
 * swallows a delete or receive failure — which is a property of this class, not of SQS. The actual
 * queue semantics (visibility timeout, redelivery, durability) are proven against real LocalStack in
 * {@code SqsQueueIntegrationTest}; a mock could not and does not stand in for that.
 */
@ExtendWith(MockitoExtension.class)
class SqsQueueConsumerTest {

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/jobs";

    @Mock
    private SqsClient sqsClient;

    @Mock
    private SqsQueueUrlProvider queueUrlProvider;

    private SqsQueueConsumer consumer;

    @BeforeEach
    void setUp() {
        AwsProperties properties = new AwsProperties("us-east-1", "test", "test",
                new AwsProperties.Sqs("http://localhost:4566", "jobs", "jobs-dlq", 30, 10, 10, false));
        consumer = new SqsQueueConsumer(sqsClient, queueUrlProvider, new ObjectMapper(), properties);
    }

    @Test
    @DisplayName("An unprocessable message is withheld from the caller but never deleted from the queue")
    void testPoisonMessageIsNeverDeleted() {
        givenQueueUrl();
        givenReceivedMessages(message("msg-poison", "rh-poison", "this is not json"));

        assertTrue(consumer.consume().isEmpty(), "A malformed message must not be handed to the caller");

        // Deleting it would destroy the only executable copy of a job that is CLAIMED in PostgreSQL.
        // The Phase 2 scheduler only rediscovers PENDING rows, so that job could never run again.
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    @DisplayName("A message that is valid JSON but violates the message contract is also never deleted")
    void testContractViolatingMessageIsNeverDeleted() {
        givenQueueUrl();
        String missingJobType = "{\"jobId\":\"" + UUID.randomUUID()
                + "\",\"payload\":\"{}\",\"retryCount\":0,\"traceId\":\"trace-1\"}";
        givenReceivedMessages(message("msg-contract", "rh-contract", missingJobType));

        assertTrue(consumer.consume().isEmpty());

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    @DisplayName("An unprocessable message does not block the valid messages in the same batch")
    void testPoisonMessageDoesNotBlockValidMessagesInSameBatch() {
        givenQueueUrl();
        UUID jobId = UUID.randomUUID();
        givenReceivedMessages(
                message("msg-poison", "rh-poison", "{ broken"),
                message("msg-valid", "rh-valid", validBody(jobId)));

        List<ReceivedJobMessage> received = consumer.consume();

        assertEquals(1, received.size());
        assertEquals(jobId, received.get(0).message().jobId());
        assertEquals("rh-valid", received.get(0).receiptHandle());
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    @DisplayName("acknowledge deletes using the receipt handle of that specific delivery")
    void testAcknowledgeDeletesWithDeliveryReceiptHandle() {
        givenQueueUrl();
        UUID jobId = UUID.randomUUID();
        givenReceivedMessages(message("msg-valid", "rh-valid", validBody(jobId)));
        ReceivedJobMessage received = consumer.consume().get(0);

        consumer.acknowledge(received);

        ArgumentCaptor<DeleteMessageRequest> captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqsClient).deleteMessage(captor.capture());
        assertEquals("rh-valid", captor.getValue().receiptHandle());
        assertEquals(QUEUE_URL, captor.getValue().queueUrl());
    }

    @Test
    @DisplayName("A failed acknowledgement is not swallowed; it surfaces with the SDK cause preserved")
    void testAcknowledgeFailureIsNotSwallowed() {
        givenQueueUrl();
        UUID jobId = UUID.randomUUID();
        givenReceivedMessages(message("msg-valid", "rh-valid", validBody(jobId)));
        ReceivedJobMessage received = consumer.consume().get(0);

        SdkException sdkFailure = SdkException.builder().message("delete rejected").build();
        when(sqsClient.deleteMessage(any(DeleteMessageRequest.class))).thenThrow(sdkFailure);

        QueueConsumeException thrown = assertThrows(QueueConsumeException.class,
                () -> consumer.acknowledge(received));

        assertTrue(thrown.getMessage().contains("Failed to acknowledge message"));
        assertSame(sdkFailure, thrown.getCause(), "The SDK failure must be preserved, not swallowed");
    }

    @Test
    @DisplayName("A failed receive is not swallowed; it surfaces with the SDK cause preserved")
    void testReceiveFailureIsNotSwallowed() {
        givenQueueUrl();
        SdkException sdkFailure = SdkException.builder().message("receive rejected").build();
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenThrow(sdkFailure);

        QueueConsumeException thrown = assertThrows(QueueConsumeException.class, () -> consumer.consume());

        assertTrue(thrown.getMessage().contains("Failed to receive messages"));
        assertSame(sdkFailure, thrown.getCause(), "The SDK failure must be preserved, not swallowed");
    }

    @Test
    @DisplayName("The receive request carries the configured visibility timeout and polling parameters")
    void testReceiveRequestUsesConfiguredValues() {
        givenQueueUrl();
        givenReceivedMessages();

        consumer.consume();

        ArgumentCaptor<ReceiveMessageRequest> captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqsClient).receiveMessage(captor.capture());
        assertEquals(30, captor.getValue().visibilityTimeout());
        assertEquals(10, captor.getValue().waitTimeSeconds());
        assertEquals(10, captor.getValue().maxNumberOfMessages());
        assertEquals(QUEUE_URL, captor.getValue().queueUrl());
    }

    private void givenQueueUrl() {
        when(queueUrlProvider.getQueueUrl()).thenReturn(QUEUE_URL);
    }

    private void givenReceivedMessages(Message... messages) {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(messages).build());
    }

    private Message message(String messageId, String receiptHandle, String body) {
        return Message.builder()
                .messageId(messageId)
                .receiptHandle(receiptHandle)
                .body(body)
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"))
                .build();
    }

    private String validBody(UUID jobId) {
        return "{\"jobId\":\"" + jobId + "\",\"jobType\":\"SEND_EMAIL\",\"payload\":\"{}\","
                + "\"retryCount\":0,\"traceId\":\"trace-1\"}";
    }
}
