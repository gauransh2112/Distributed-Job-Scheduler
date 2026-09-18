package com.scheduler.job.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Configuration properties for job scheduler polling and claiming parameters.
 *
 * <p>The operational values carry no Java-side fallbacks. {@code scheduler.poll-interval-ms} and
 * {@code scheduler.batch-size} are declared in application.yml, so a field initializer here would be
 * unreachable duplication and a second, silent source of truth. Leaving them unset makes the bound
 * value zero, which the {@code @Positive} constraints below reject at startup: a misconfiguration
 * fails loudly instead of running with an invented value.
 *
 * <p>{@code instanceId} is the deliberate exception. It is not a configuration fallback but a per-JVM
 * identity, and it is intentionally absent from application.yml: a shared value there would make every
 * instance claim jobs under the same identity. The generated default gives each JVM a distinct id, and
 * {@code SCHEDULER_INSTANCE_ID} overrides it per instance. Removing it would leave the id null.
 */
@Validated
@ConfigurationProperties(prefix = "scheduler")
public class SchedulerProperties {

    @Positive(message = "pollIntervalMs must be greater than zero")
    private long pollIntervalMs;

    @Positive(message = "batchSize must be greater than zero")
    private int batchSize;

    /** Per-JVM claiming identity; see the class javadoc for why this default is load-bearing. */
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
