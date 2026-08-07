package com.scheduler.job.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Immutable DTO record for job submission requests.
 */
public record CreateJobRequest(
        @NotBlank(message = "Job type is required")
        @Size(max = 100, message = "Job type must not exceed 100 characters")
        String jobType,

        @NotBlank(message = "Payload is required")
        String payload,

        @NotNull(message = "Scheduled time is required")
        @FutureOrPresent(message = "Scheduled time must be in the present or future")
        Instant scheduledAt,

        @NotBlank(message = "Idempotency key is required")
        @Size(max = 200, message = "Idempotency key must not exceed 200 characters")
        String idempotencyKey
) {}
