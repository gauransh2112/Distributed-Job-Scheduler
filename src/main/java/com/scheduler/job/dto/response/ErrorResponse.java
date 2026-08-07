package com.scheduler.job.dto.response;

import java.time.Instant;

/**
 * Immutable DTO record for standardized REST API error responses.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {}
