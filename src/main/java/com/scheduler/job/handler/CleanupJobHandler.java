package com.scheduler.job.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Demo handler that simulates deleting temporary resources.
 *
 * <p>Expects a payload of the form {@code {"target": "TEMP_FILES", "olderThanDays": 30}}, where
 * {@code olderThanDays} is optional and must be non-negative when present.
 *
 * <p>Stateless: the only field is an immutable, thread-safe {@link ObjectMapper}.
 */
@Component
public class CleanupJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(CleanupJobHandler.class);

    static final String JOB_TYPE = "CLEANUP";
    static final int DEFAULT_OLDER_THAN_DAYS = 7;

    private final ObjectMapper objectMapper;

    public CleanupJobHandler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(String payload) {
        PayloadReader reader = PayloadReader.of(objectMapper, payload, JOB_TYPE);
        String target = reader.requireText("target");
        int olderThanDays = reader.optionalNonNegativeInt("olderThanDays", DEFAULT_OLDER_THAN_DAYS);

        log.info("Cleaned up temporary resources: job_type={}, target={}, older_than_days={}",
                JOB_TYPE, target, olderThanDays);
    }
}
