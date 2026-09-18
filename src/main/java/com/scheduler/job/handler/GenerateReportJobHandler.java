package com.scheduler.job.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Demo handler that simulates generating a report and storing its output.
 *
 * <p>Expects a payload of the form {@code {"reportType": "MONTHLY_SALES", "format": "CSV"}}, where
 * {@code format} is optional.
 *
 * <p>Stateless: the only field is an immutable, thread-safe {@link ObjectMapper}.
 */
@Component
public class GenerateReportJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(GenerateReportJobHandler.class);

    static final String JOB_TYPE = "GENERATE_REPORT";
    static final String DEFAULT_FORMAT = "CSV";

    private final ObjectMapper objectMapper;

    public GenerateReportJobHandler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(String payload) {
        PayloadReader reader = PayloadReader.of(objectMapper, payload, JOB_TYPE);
        String reportType = reader.requireText("reportType");
        String format = reader.optionalText("format", DEFAULT_FORMAT);

        String output = render(reportType, format);

        log.info("Generated report: job_type={}, report_type={}, format={}, output_bytes={}",
                JOB_TYPE, reportType, format, output.length());
    }

    private String render(String reportType, String format) {
        // Stands in for real report generation; deterministic so the demo stays testable.
        return String.format("report=%s;format=%s", reportType, format);
    }
}
