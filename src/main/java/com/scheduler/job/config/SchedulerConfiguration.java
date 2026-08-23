package com.scheduler.job.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Spring configuration class enabling scheduling, binding scheduler properties,
 * and registering Clock bean for time-dependent operations.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({JobProperties.class, SchedulerProperties.class})
public class SchedulerConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
