package com.scheduler.job.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the queue message contract. Pure serialization logic, no infrastructure involved.
 */
class JobMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("JobMessage carries exactly the five contract fields and nothing from the JPA entity")
    void testMessageContainsOnlyContractFields() throws Exception {
        JobEntity job = job(UUID.randomUUID(), "SEND_EMAIL", 2);
        job.setClaimedBy("scheduler-1");
        job.setIdempotencyKey("IDEM-SECRET");
        job.setLastError("previous failure");

        ObjectNode node = (ObjectNode) objectMapper.readTree(
                objectMapper.writeValueAsString(JobMessage.from(job, "trace-1")));

        assertEquals(5, node.size(), "Message must contain exactly jobId, jobType, payload, retryCount, traceId");
        assertTrue(node.has("jobId"));
        assertTrue(node.has("jobType"));
        assertTrue(node.has("payload"));
        assertTrue(node.has("retryCount"));
        assertTrue(node.has("traceId"));
        assertFalse(node.has("claimedBy"), "Persistence-only fields must not reach the queue");
        assertFalse(node.has("idempotencyKey"), "Persistence-only fields must not reach the queue");
        assertFalse(node.has("status"), "Queue messages must not carry job state; PostgreSQL owns state");
    }

    @Test
    @DisplayName("JobMessage survives a serialize/deserialize round trip without losing fields")
    void testSerializationRoundTrip() throws Exception {
        UUID jobId = UUID.randomUUID();
        JobMessage original = JobMessage.from(job(jobId, "GENERATE_REPORT", 3), "trace-round-trip");

        JobMessage restored = objectMapper.readValue(objectMapper.writeValueAsString(original), JobMessage.class);

        assertEquals(original, restored);
        assertEquals(jobId, restored.jobId());
        assertEquals("GENERATE_REPORT", restored.jobType());
        assertEquals("{\"to\":\"user@example.com\"}", restored.payload());
        assertEquals(3, restored.retryCount());
        assertEquals("trace-round-trip", restored.traceId());
    }

    @Test
    @DisplayName("Deserializing a message with a missing required field is rejected, not silently defaulted")
    void testDeserializationRejectsMissingRequiredField() {
        String bodyWithoutJobType = "{\"jobId\":\"" + UUID.randomUUID()
                + "\",\"payload\":\"{}\",\"retryCount\":0,\"traceId\":\"trace-1\"}";

        assertThrows(Exception.class, () -> objectMapper.readValue(bodyWithoutJobType, JobMessage.class));
    }

    @Test
    @DisplayName("Deserializing a message with a negative retryCount is rejected")
    void testDeserializationRejectsNegativeRetryCount() {
        String body = "{\"jobId\":\"" + UUID.randomUUID()
                + "\",\"jobType\":\"SEND_EMAIL\",\"payload\":\"{}\",\"retryCount\":-1,\"traceId\":\"trace-1\"}";

        assertThrows(Exception.class, () -> objectMapper.readValue(body, JobMessage.class));
    }

    @Test
    @DisplayName("Constructing a JobMessage without a jobId is rejected")
    void testNullJobIdRejected() {
        assertThrows(NullPointerException.class,
                () -> new JobMessage(null, "SEND_EMAIL", "{}", 0, "trace-1"));
    }

    @Test
    @DisplayName("Constructing a JobMessage with a blank jobType or traceId is rejected")
    void testBlankFieldsRejected() {
        UUID jobId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new JobMessage(jobId, "  ", "{}", 0, "trace-1"));
        assertThrows(IllegalArgumentException.class, () -> new JobMessage(jobId, "SEND_EMAIL", "{}", 0, " "));
        assertThrows(IllegalArgumentException.class, () -> new JobMessage(jobId, "SEND_EMAIL", " ", 0, "trace-1"));
    }

    private JobEntity job(UUID id, String jobType, int retryCount) {
        return JobEntity.builder()
                .id(id)
                .jobType(jobType)
                .payload("{\"to\":\"user@example.com\"}")
                .status(JobStatus.CLAIMED)
                .scheduledAt(Instant.now())
                .retryCount(retryCount)
                .maxRetries(5)
                .build();
    }
}
