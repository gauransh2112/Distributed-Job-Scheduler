package com.scheduler.job.dto.response;

import com.scheduler.job.entity.JobStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable DTO record returned in job listing and paginated query results.
 */
public record JobSummaryResponse(
        UUID id,
        String jobType,
        JobStatus status,
        Instant scheduledAt,
        Instant createdAt
) {}
