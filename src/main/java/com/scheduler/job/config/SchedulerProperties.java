package com.scheduler.job.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Configuration properties for job scheduler polling and claiming parameters.
 */
@Validated
@ConfigurationProperties(prefix = "scheduler")
public class SchedulerProperties {

    @Positive(message = "pollIntervalMs must be greater than zero")
    private long pollIntervalMs = 5000;

    @Positive(message = "batchSize must be greater than zero")
    private int batchSize = 50;

    private String instanceId = "scheduler-" + UUID.randomUUID().toString().substring(0, 8);

    public SchedulerProperties() {
    }

    public SchedulerProperties(long pollIntervalMs, int batchSize, String instanceId) {
        this.pollIntervalMs = pollIntervalMs;
        this.batchSize = batchSize;
        this.instanceId = instanceId;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }
}
