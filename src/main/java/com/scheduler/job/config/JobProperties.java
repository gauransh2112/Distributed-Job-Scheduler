package com.scheduler.job.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for scheduler default parameters.
 */
@ConfigurationProperties(prefix = "scheduler.defaults")
public record JobProperties(
        int maxRetries,
        int maxPageSize
) {
    public JobProperties {
        if (maxRetries < 0) {
            maxRetries = 5;
        }
        if (maxPageSize <= 0) {
            maxPageSize = 100;
        }
    }
}
