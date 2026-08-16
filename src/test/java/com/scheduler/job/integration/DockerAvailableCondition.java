package com.scheduler.job.integration;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

/**
 * Custom JUnit 5 ExecutionCondition that enables Testcontainers tests if Docker daemon is running,
 * and skips them gracefully with diagnosable reasons if Docker is not available.
 */
public class DockerAvailableCondition implements ExecutionCondition {

    private static final Logger log = LoggerFactory.getLogger(DockerAvailableCondition.class);

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Docker daemon is available for Testcontainers integration testing");
            }
            log.info("Docker daemon is not available. Testcontainers integration tests will be skipped.");
            return ConditionEvaluationResult.disabled("Docker daemon is not available on host system");
        } catch (Throwable t) {
            log.warn("Docker environment check failed: {} - {}", t.getClass().getName(), t.getMessage());
            return ConditionEvaluationResult.disabled(String.format("Docker environment check failed [%s]: %s",
                    t.getClass().getSimpleName(), t.getMessage()));
        }
    }
}
