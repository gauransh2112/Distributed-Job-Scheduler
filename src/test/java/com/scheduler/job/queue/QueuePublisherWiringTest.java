package com.scheduler.job.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.job.config.SqsConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the temporary {@link NoopQueuePublisher} cannot become the production implementation.
 *
 * <p>The two publishers are mutually exclusive: SQS is selected whenever {@code aws.sqs.enabled} is
 * absent or true, and the no-op publisher requires an explicit {@code aws.sqs.enabled=false} opt-out.
 */
class QueuePublisherWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withConfiguration(UserConfigurations.of(
                    SqsConfiguration.class,
                    SqsQueueUrlProvider.class,
                    SqsQueuePublisher.class,
                    SqsQueueConsumer.class,
                    NoopQueuePublisher.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues(
                    "aws.region=us-east-1",
                    "aws.access-key-id=test",
                    "aws.secret-access-key=test",
                    "aws.sqs.endpoint=http://localhost:4566",
                    "aws.sqs.queue-name=test-jobs",
                    "aws.sqs.dlq-name=test-jobs-dlq",
                    "aws.sqs.visibility-timeout-seconds=30",
                    "aws.sqs.wait-time-seconds=0",
                    "aws.sqs.max-messages-per-poll=10",
                    "aws.sqs.auto-create-queues=false");

    @Test
    @DisplayName("By default the SQS publisher is wired and the no-op publisher is absent")
    void testSqsPublisherIsTheDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(QueuePublisher.class);
            assertThat(context).getBean(QueuePublisher.class).isInstanceOf(SqsQueuePublisher.class);
            assertThat(context).doesNotHaveBean(NoopQueuePublisher.class);
            assertThat(context).hasSingleBean(QueueConsumer.class);
        });
    }

    @Test
    @DisplayName("Explicitly enabling SQS still wires the SQS publisher only")
    void testSqsPublisherWhenExplicitlyEnabled() {
        contextRunner.withPropertyValues("aws.sqs.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(QueuePublisher.class);
            assertThat(context).getBean(QueuePublisher.class).isInstanceOf(SqsQueuePublisher.class);
            assertThat(context).doesNotHaveBean(NoopQueuePublisher.class);
        });
    }

    @Test
    @DisplayName("The no-op publisher requires an explicit aws.sqs.enabled=false opt-out and excludes SQS beans")
    void testNoopPublisherRequiresExplicitOptOut() {
        contextRunner.withPropertyValues("aws.sqs.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(QueuePublisher.class);
            assertThat(context).getBean(QueuePublisher.class).isInstanceOf(NoopQueuePublisher.class);
            assertThat(context).doesNotHaveBean(SqsQueuePublisher.class);
            assertThat(context).doesNotHaveBean(QueueConsumer.class);
        });
    }

    @Test
    @DisplayName("Invalid SQS configuration fails fast instead of being silently defaulted")
    void testInvalidConfigurationFailsFast() {
        contextRunner.withPropertyValues("aws.sqs.max-messages-per-poll=11").run(context ->
                assertThat(context).hasFailed());

        contextRunner.withPropertyValues("aws.sqs.wait-time-seconds=21").run(context ->
                assertThat(context).hasFailed());

        contextRunner.withPropertyValues("aws.sqs.queue-name=").run(context ->
                assertThat(context).hasFailed());
    }
}
