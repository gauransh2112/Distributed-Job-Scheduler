package com.scheduler.job.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for scheduler default parameters.
 */
@Validated
@ConfigurationProperties(prefix = "scheduler.defaults")
public record JobProperties(
        @Min(value = 0, message = "maxRetries cannot be negative")
        int maxRetries,

        @Positive(message = "maxPageSize must be greater than zero")
        int maxPageSize
) {
}
