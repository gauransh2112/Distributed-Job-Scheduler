package com.scheduler.job.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;

/**
 * Creates the AWS SQS client used by {@code SqsQueuePublisher} and {@code SqsQueueConsumer}.
 *
 * <p>Activated unless {@code aws.sqs.enabled=false} is set explicitly, which is the switch that keeps the
 * temporary {@code NoopQueuePublisher} out of any normal execution path: SQS is the default and must be
 * opted out of deliberately.
 *
 * <p>The client is created eagerly but performs no network calls at construction time, so application
 * startup does not depend on LocalStack/AWS being reachable. Queue URLs are resolved lazily on first use.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
@ConditionalOnProperty(prefix = "aws.sqs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SqsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SqsConfiguration.class);

    @Bean(destroyMethod = "close")
    public SqsClient sqsClient(AwsProperties awsProperties) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(awsProperties.region()));

        if (awsProperties.hasStaticCredentials()) {
            // Static credentials exist for LocalStack; the secret value is never logged.
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(awsProperties.accessKeyId(), awsProperties.secretAccessKey())));
            log.info("SQS client configured with static credentials (accessKeyId='{}', region='{}')",
                    awsProperties.accessKeyId(), awsProperties.region());
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
            log.info("SQS client configured with the AWS default credentials provider chain (region='{}')",
                    awsProperties.region());
        }

        AwsProperties.Sqs sqs = awsProperties.sqs();
        if (sqs.hasEndpointOverride()) {
            builder.endpointOverride(URI.create(sqs.endpoint()));
            log.info("SQS endpoint overridden to '{}' (LocalStack/dev endpoint)", sqs.endpoint());
        }

        log.info("SQS queues configured: main='{}', dlq='{}', visibilityTimeout={}s, waitTime={}s, maxMessagesPerPoll={}",
                sqs.queueName(), sqs.dlqName(), sqs.visibilityTimeoutSeconds(), sqs.waitTimeSeconds(),
                sqs.maxMessagesPerPoll());

        return builder.build();
    }
}
