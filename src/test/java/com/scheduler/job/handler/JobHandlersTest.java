package com.scheduler.job.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.job.exception.JobExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the concrete demo handlers: the happy path, and payload validation failures surfacing
 * as {@link JobExecutionException} rather than raw parsing errors.
 */
class JobHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("SendEmailJobHandler")
    class SendEmail {

        private final SendEmailJobHandler handler = new SendEmailJobHandler(objectMapper);

        @Test
        @DisplayName("serves SEND_EMAIL and executes a valid payload")
        void testValidPayload() {
            assertEquals("SEND_EMAIL", handler.jobType());
            assertDoesNotThrow(() -> handler.execute(
                    "{\"to\":\"user@example.com\",\"template\":\"WELCOME_EMAIL\",\"userId\":1001}"));
        }

        @Test
        @DisplayName("rejects a payload missing the recipient or template")
        void testMissingFields() {
            assertThrows(JobExecutionException.class,
                    () -> handler.execute("{\"template\":\"WELCOME_EMAIL\"}"));
            assertThrows(JobExecutionException.class,
                    () -> handler.execute("{\"to\":\"user@example.com\"}"));
            assertThrows(JobExecutionException.class,
                    () -> handler.execute("{\"to\":\"  \",\"template\":\"WELCOME_EMAIL\"}"));
        }

        @Test
        @DisplayName("does not leak the recipient into the failure message")
        void testFailureMessageHasNoPayloadValues() {
            JobExecutionException thrown = assertThrows(JobExecutionException.class,
                    () -> handler.execute("{\"to\":\"secret.person@example.com\"}"));

            assertFalse(thrown.getMessage().contains("secret.person@example.com"),
                    "Failure messages reach last_error and the logs, so payload values must stay out");
            assertTrue(thrown.getMessage().contains("template"));
        }
    }

    @Nested
    @DisplayName("GenerateReportJobHandler")
    class GenerateReport {

        private final GenerateReportJobHandler handler = new GenerateReportJobHandler(objectMapper);

        @Test
        @DisplayName("serves GENERATE_REPORT and executes a valid payload")
        void testValidPayload() {
            assertEquals("GENERATE_REPORT", handler.jobType());
            assertDoesNotThrow(() -> handler.execute(
                    "{\"reportType\":\"MONTHLY_SALES\",\"format\":\"PDF\"}"));
        }

        @Test
        @DisplayName("defaults the optional format")
        void testOptionalFormatDefaults() {
            assertDoesNotThrow(() -> handler.execute("{\"reportType\":\"MONTHLY_SALES\"}"));
        }

        @Test
        @DisplayName("rejects a payload without a report type")
        void testMissingReportType() {
            assertThrows(JobExecutionException.class, () -> handler.execute("{\"format\":\"PDF\"}"));
        }
    }

    @Nested
    @DisplayName("CleanupJobHandler")
    class Cleanup {

        private final CleanupJobHandler handler = new CleanupJobHandler(objectMapper);

        @Test
        @DisplayName("serves CLEANUP and executes a valid payload")
        void testValidPayload() {
            assertEquals("CLEANUP", handler.jobType());
            assertDoesNotThrow(() -> handler.execute(
                    "{\"target\":\"TEMP_FILES\",\"olderThanDays\":30}"));
        }

        @Test
        @DisplayName("defaults the optional retention window")
        void testOptionalRetentionDefaults() {
            assertDoesNotThrow(() -> handler.execute("{\"target\":\"TEMP_FILES\"}"));
        }

        @Test
        @DisplayName("rejects a missing target or a negative retention window")
        void testInvalidPayloads() {
            assertThrows(JobExecutionException.class, () -> handler.execute("{\"olderThanDays\":30}"));
            assertThrows(JobExecutionException.class,
                    () -> handler.execute("{\"target\":\"TEMP_FILES\",\"olderThanDays\":-1}"));
            assertThrows(JobExecutionException.class,
                    () -> handler.execute("{\"target\":\"TEMP_FILES\",\"olderThanDays\":\"soon\"}"));
        }
    }

    @Nested
    @DisplayName("Payload handling shared by every handler")
    class PayloadHandling {

        @Test
        @DisplayName("malformed, blank and non-object payloads surface as JobExecutionException")
        void testMalformedPayloads() {
            for (JobHandler handler : allHandlers()) {
                assertThrows(JobExecutionException.class, () -> handler.execute("not json"),
                        handler.jobType() + " must reject unparseable payloads");
                assertThrows(JobExecutionException.class, () -> handler.execute(""),
                        handler.jobType() + " must reject a blank payload");
                assertThrows(JobExecutionException.class, () -> handler.execute(null),
                        handler.jobType() + " must reject a null payload");
                assertThrows(JobExecutionException.class, () -> handler.execute("[1,2,3]"),
                        handler.jobType() + " must reject a payload that is not a JSON object");
            }
        }

        @Test
        @DisplayName("a raw parsing error never escapes as a Jackson exception")
        void testParsingErrorsAreWrapped() {
            for (JobHandler handler : allHandlers()) {
                JobExecutionException thrown = assertThrows(JobExecutionException.class,
                        () -> handler.execute("{oops"));
                assertTrue(thrown.getMessage().contains(handler.jobType()));
            }
        }
    }

    private java.util.List<JobHandler> allHandlers() {
        return java.util.List.of(
                new SendEmailJobHandler(objectMapper),
                new GenerateReportJobHandler(objectMapper),
                new CleanupJobHandler(objectMapper));
    }
}
