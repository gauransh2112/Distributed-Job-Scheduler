package com.scheduler.job.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for AWS connectivity and the SQS queues used by the execution engine.
 *
 * <p>No Java-side fallback values are declared: every operational value must be supplied by
 * configuration (application.yml, profile overrides, or environment variables) so that queue names,
 * timeouts and polling parameters are never silently defaulted by the code.
 */
@Validated
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(

        @NotBlank(message = "aws.region must be configured")
        String region,

        /*
         * Static credentials are intended for LocalStack / local development only.
         * When left blank the AWS default credentials provider chain is used instead,
         * so deployed environments can rely on instance/container roles.
         */
        String accessKeyId,

        String secretAccessKey,

        @NotNull(message = "aws.sqs configuration block must be present")
        @Valid
        Sqs sqs
) {

    /**
     * SQS specific configuration.
     *
     * @param endpoint                 endpoint override (LocalStack). Blank/absent means real AWS endpoints.
     * @param queueName                name of the main job execution queue
     * @param dlqName                  name of the dead letter queue
     * @param visibilityTimeoutSeconds how long a received message stays invisible to other consumers
     * @param waitTimeSeconds          long-poll wait time (0 disables long polling, SQS maximum is 20)
     * @param maxMessagesPerPoll       messages requested per receive call (SQS maximum is 10)
     * @param autoCreateQueues         create queues on first use when missing; intended for local/dev and tests
     */
    public record Sqs(

            String endpoint,

            @NotBlank(message = "aws.sqs.queue-name must be configured")
            String queueName,

            @NotBlank(message = "aws.sqs.dlq-name must be configured")
            String dlqName,

            @Positive(message = "aws.sqs.visibility-timeout-seconds must be greater than zero")
            int visibilityTimeoutSeconds,

            @Min(value = 0, message = "aws.sqs.wait-time-seconds cannot be negative")
            @Max(value = 20, message = "aws.sqs.wait-time-seconds cannot exceed the SQS maximum of 20")
            int waitTimeSeconds,

            @Min(value = 1, message = "aws.sqs.max-messages-per-poll must be at least 1")
            @Max(value = 10, message = "aws.sqs.max-messages-per-poll cannot exceed the SQS maximum of 10")
            int maxMessagesPerPoll,

            boolean autoCreateQueues
    ) {

        /**
         * @return true when an endpoint override (LocalStack) has been configured
         */
        public boolean hasEndpointOverride() {
            return endpoint != null && !endpoint.isBlank();
        }
    }

    /**
     * @return true when explicit static credentials were supplied (LocalStack / local development)
     */
    public boolean hasStaticCredentials() {
        return accessKeyId != null && !accessKeyId.isBlank()
                && secretAccessKey != null && !secretAccessKey.isBlank();
    }
}
