package com.scheduler.job.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.job.config.JobProperties;
import com.scheduler.job.dto.request.CreateJobRequest;
import com.scheduler.job.dto.response.CreateJobResponse;
import com.scheduler.job.dto.response.JobResponse;
import com.scheduler.job.dto.response.JobSummaryResponse;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.DuplicateJobException;
import com.scheduler.job.exception.GlobalExceptionHandler;
import com.scheduler.job.exception.JobNotFoundException;
import com.scheduler.job.service.JobQueryService;
import com.scheduler.job.service.JobSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobController.class)
@Import(GlobalExceptionHandler.class)
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JobSubmissionService jobSubmissionService;

    @MockBean
    private JobQueryService jobQueryService;

    @MockBean
    private JobProperties jobProperties;

    @BeforeEach
    void setUp() {
        when(jobProperties.maxPageSize()).thenReturn(100);
        when(jobProperties.maxRetries()).thenReturn(5);
    }

    @Test
    @DisplayName("POST /api/v1/jobs - Should return 201 Created with Location header on successful submission")
    void createJob_Success() throws Exception {
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        CreateJobRequest request = new CreateJobRequest(
                "SEND_EMAIL",
                "{\"to\":\"test@example.com\"}",
                now.plusSeconds(300),
                "IDEM-KEY-001"
        );

        CreateJobResponse response = new CreateJobResponse(
                jobId,
                request.jobType(),
                JobStatus.PENDING,
                request.scheduledAt(),
                request.idempotencyKey(),
                now
        );

        when(jobSubmissionService.createJob(any(CreateJobRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/jobs/" + jobId))
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.jobType").value("SEND_EMAIL"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.idempotencyKey").value("IDEM-KEY-001"));
    }

    @Test
    @DisplayName("POST /api/v1/jobs - Should return 409 Conflict when idempotency key is duplicate")
    void createJob_DuplicateIdempotencyKey_Returns409() throws Exception {
        CreateJobRequest request = new CreateJobRequest(
                "SEND_EMAIL",
                "{\"to\":\"test@example.com\"}",
                Instant.now().plusSeconds(300),
                "IDEM-KEY-001"
        );

        when(jobSubmissionService.createJob(any(CreateJobRequest.class)))
                .thenThrow(new DuplicateJobException("IDEM-KEY-001"));

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Job with idempotency key 'IDEM-KEY-001' already exists"));
    }

    @Test
    @DisplayName("POST /api/v1/jobs - Should return 400 Bad Request when request body fails Bean Validation")
    void createJob_InvalidRequest_Returns400() throws Exception {
        CreateJobRequest invalidRequest = new CreateJobRequest(
                "", // blank jobType
                "", // blank payload
                Instant.now().minusSeconds(600), // past scheduledAt
                ""  // blank idempotencyKey
        );

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("GET /api/v1/jobs/{id} - Should return 200 OK when job exists")
    void getJob_Success() throws Exception {
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        JobResponse response = new JobResponse(
                jobId,
                "GENERATE_REPORT",
                "{\"reportId\":10}",
                JobStatus.PENDING,
                now.plusSeconds(600),
                null,
                0,
                5,
                null,
                "IDEM-KEY-100",
                now,
                now
        );

        when(jobQueryService.getJob(jobId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/jobs/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.jobType").value("GENERATE_REPORT"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.maxRetries").value(5));
    }

    @Test
    @DisplayName("GET /api/v1/jobs/{id} - Should return 404 Not Found when job does not exist")
    void getJob_NotFound_Returns404() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobQueryService.getJob(jobId)).thenThrow(new JobNotFoundException(jobId));

        mockMvc.perform(get("/api/v1/jobs/{id}", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    @DisplayName("GET /api/v1/jobs - Should return 200 OK with paginated job list")
    void listJobs_Success() throws Exception {
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        JobSummaryResponse summary = new JobSummaryResponse(
                jobId,
                "CLEANUP",
                JobStatus.PENDING,
                now.plusSeconds(100),
                now
        );

        when(jobQueryService.listJobs(eq(JobStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary)));

        mockMvc.perform(get("/api/v1/jobs")
                        .param("status", "PENDING")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(jobId.toString()))
                .andExpect(jsonPath("$.content[0].jobType").value("CLEANUP"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /api/v1/jobs - Should return 400 Bad Request when page is negative")
    void listJobs_NegativePage_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/jobs")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Page index cannot be negative"));
    }

    @Test
    @DisplayName("GET /api/v1/jobs - Should return 400 Bad Request when size exceeds configured maxPageSize")
    void listJobs_ExcessiveSize_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/jobs")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Page size must be between 1 and 100"));
    }

    @Test
    @DisplayName("GET /api/v1/jobs - Should return 400 Bad Request when sortBy field is not allowed")
    void listJobs_InvalidSortBy_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/jobs")
                        .param("sortBy", "invalidField"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(startsWith("Invalid sortBy field 'invalidField'")));
    }

    @Test
    @DisplayName("GET /api/v1/jobs - Should return 400 Bad Request when sortOrder is not ASC or DESC")
    void listJobs_InvalidSortOrder_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/jobs")
                        .param("sortOrder", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Invalid sortOrder 'INVALID'. Allowed values: ASC, DESC"));
    }
}
