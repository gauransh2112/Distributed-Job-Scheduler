package com.scheduler.job.dto.response;

import com.scheduler.job.entity.JobStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable DTO record containing complete detailed job information.
 */
public record JobResponse(
        UUID id,
        String jobType,
        String payload,
        JobStatus status,
        Instant scheduledAt,
        String claimedBy,
        int retryCount,
        int maxRetries,
        String lastError,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt
) {}
