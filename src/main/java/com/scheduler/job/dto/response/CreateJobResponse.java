package com.scheduler.job.dto.response;

import com.scheduler.job.entity.JobStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable DTO record returned after successful job creation.
 */
public record CreateJobResponse(
        UUID id,
        String jobType,
        JobStatus status,
        Instant scheduledAt,
        String idempotencyKey,
        Instant createdAt
) {}
