package com.scheduler.job.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Demo handler that simulates sending a templated email.
 *
 * <p>Expects a payload of the form
 * {@code {"to": "user@example.com", "template": "WELCOME_EMAIL", "userId": 1001}}.
 *
 * <p>Stateless: the only field is an immutable, thread-safe {@link ObjectMapper}.
 */
@Component
public class SendEmailJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(SendEmailJobHandler.class);

    static final String JOB_TYPE = "SEND_EMAIL";

    private final ObjectMapper objectMapper;

    public SendEmailJobHandler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(String payload) {
        PayloadReader reader = PayloadReader.of(objectMapper, payload, JOB_TYPE);
        String recipient = reader.requireText("to");
        String template = reader.requireText("template");

        // Recipient is masked: a payload can carry personal data and this line goes to the logs.
        log.info("Sending email: job_type={}, template={}, recipient={}",
                JOB_TYPE, template, PayloadReader.mask(recipient));
    }
}
