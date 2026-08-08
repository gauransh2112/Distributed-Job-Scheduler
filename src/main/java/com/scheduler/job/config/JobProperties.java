package com.scheduler.job.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for job scheduler defaults.
 */
@ConfigurationProperties(prefix = "scheduler.defaults")
public record JobProperties(
        int maxRetries
) {}
