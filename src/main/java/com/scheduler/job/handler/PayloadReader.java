package com.scheduler.job.handler;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.job.exception.JobExecutionException;

import java.util.Objects;

/**
 * Reads and validates fields from a job payload, turning every problem into a
 * {@link JobExecutionException} that names the job type and the offending field.
 *
 * <p>Created per execution as a local value, so handlers stay stateless. Payload values are never
 * placed in exception messages: a message travels into {@code last_error} and the logs, and payloads
 * can carry personal data.
 */
final class PayloadReader {

    private final String jobType;
    private final JsonNode root;

    private PayloadReader(String jobType, JsonNode root) {
        this.jobType = jobType;
        this.root = root;
    }

    /**
     * Parses a payload, requiring a JSON object.
     *
     * @throws JobExecutionException if the payload is blank, unparseable, or not a JSON object
     */
    static PayloadReader of(ObjectMapper objectMapper, String payload, String jobType) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        if (payload == null || payload.isBlank()) {
            throw new JobExecutionException(jobType + " payload is missing");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JacksonException e) {
            throw new JobExecutionException(jobType + " payload is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new JobExecutionException(jobType + " payload must be a JSON object");
        }
        return new PayloadReader(jobType, root);
    }

    /**
     * @throws JobExecutionException if the field is absent, not textual, or blank
     */
    String requireText(String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            throw new JobExecutionException(String.format(
                    "%s payload requires a non-blank text field '%s'", jobType, field));
        }
        return value.asText();
    }

    /**
     * @throws JobExecutionException if the field is present but not a non-negative integer
     */
    int optionalNonNegativeInt(String field, int defaultValue) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isInt() || value.asInt() < 0) {
            throw new JobExecutionException(String.format(
                    "%s payload field '%s' must be a non-negative integer", jobType, field));
        }
        return value.asInt();
    }

    String optionalText(String field, String defaultValue) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()
                ? defaultValue
                : value.asText();
    }

    /**
     * Masks a value that may identify a person, for logging.
     */
    static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "(none)";
        }
        int at = value.indexOf('@');
        if (at > 0) {
            return value.charAt(0) + "***" + value.substring(at);
        }
        return value.charAt(0) + "***";
    }
}
