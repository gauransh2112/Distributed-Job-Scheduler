package com.scheduler.job.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the configuration contract for the scheduler.
 *
 * <p>{@code pollIntervalMs} and {@code batchSize} carry no Java-side default, so these tests pin the
 * behaviour that makes that safe: a missing or non-positive value must fail startup rather than run
 * with an invented number. {@code instanceId} keeps its generated default, and that is asserted here
 * too, because removing it would silently leave every instance claiming under a null identity.
 */
class SchedulerPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withConfiguration(UserConfigurations.of(SchedulerPropertiesTestConfig.class));

    @Test
    @DisplayName("Operational values bind from configuration")
    void testValuesBindFromConfiguration() {
        contextRunner
                .withPropertyValues("scheduler.poll-interval-ms=1234", "scheduler.batch-size=17")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SchedulerProperties properties = context.getBean(SchedulerProperties.class);
                    assertThat(properties.getPollIntervalMs()).isEqualTo(1234);
                    assertThat(properties.getBatchSize()).isEqualTo(17);
                });
    }

    @Test
    @DisplayName("A missing poll interval fails startup instead of falling back to a Java default")
    void testMissingPollIntervalFailsFast() {
        contextRunner.withPropertyValues("scheduler.batch-size=17")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("A missing batch size fails startup instead of falling back to a Java default")
    void testMissingBatchSizeFailsFast() {
        contextRunner.withPropertyValues("scheduler.poll-interval-ms=1234")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("A non-positive value is rejected")
    void testNonPositiveValuesRejected() {
        contextRunner.withPropertyValues("scheduler.poll-interval-ms=0", "scheduler.batch-size=17")
                .run(context -> assertThat(context).hasFailed());

        contextRunner.withPropertyValues("scheduler.poll-interval-ms=1234", "scheduler.batch-size=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("instanceId keeps its generated per-JVM default when configuration does not set it")
    void testInstanceIdDefaultIsPreserved() {
        contextRunner
                .withPropertyValues("scheduler.poll-interval-ms=1234", "scheduler.batch-size=17")
                .run(context -> {
                    SchedulerProperties properties = context.getBean(SchedulerProperties.class);
                    assertThat(properties.getInstanceId())
                            .as("Removing this default would leave every instance claiming under a null identity")
                            .isNotNull()
                            .isNotBlank()
                            .startsWith("scheduler-");
                });
    }

    @Test
    @DisplayName("instanceId is still overridable per instance")
    void testInstanceIdOverridable() {
        contextRunner
                .withPropertyValues("scheduler.poll-interval-ms=1234", "scheduler.batch-size=17",
                        "scheduler.instance-id=scheduler-node-7")
                .run(context -> assertThat(context.getBean(SchedulerProperties.class).getInstanceId())
                        .isEqualTo("scheduler-node-7"));
    }

    @Test
    @DisplayName("Separately constructed instances get distinct generated identities")
    void testGeneratedIdentitiesAreDistinct() {
        assertThat(new SchedulerProperties().getInstanceId())
                .isNotEqualTo(new SchedulerProperties().getInstanceId());
    }

    @Configuration
    @EnableConfigurationProperties(SchedulerProperties.class)
    static class SchedulerPropertiesTestConfig {
    }
}
