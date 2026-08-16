package com.scheduler.job.integration;

import com.scheduler.job.dto.request.CreateJobRequest;
import com.scheduler.job.dto.response.CreateJobResponse;
import com.scheduler.job.dto.response.JobResponse;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.DuplicateJobException;
import com.scheduler.job.repository.JobRepository;
import com.scheduler.job.service.JobSubmissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
class JobIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test_scheduler_db")
            .withUsername("test_user")
            .withPassword("test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobSubmissionService jobSubmissionService;

    @AfterEach
    void tearDown() {
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("Full HTTP Lifecycle - Submit job and retrieve details from real PostgreSQL database")
    void testFullJobLifecycle_SubmitAndQuery() {
        String baseUrl = "http://localhost:" + port + "/api/v1/jobs";
        CreateJobRequest request = new CreateJobRequest(
                "GENERATE_INVOICE",
                "{\"invoiceId\":5001}",
                Instant.now().plusSeconds(300),
                "IDEM-INV-5001"
        );

        ResponseEntity<CreateJobResponse> postResponse = restTemplate.postForEntity(baseUrl, request, CreateJobResponse.class);

        assertEquals(HttpStatus.CREATED, postResponse.getStatusCode());
        assertNotNull(postResponse.getHeaders().getLocation());
        CreateJobResponse body = postResponse.getBody();
        assertNotNull(body);
        assertNotNull(body.id());
        assertEquals("GENERATE_INVOICE", body.jobType());
        assertEquals(JobStatus.PENDING, body.status());

        // Verify entity persisted in real PostgreSQL DB
        Optional<JobEntity> persistedOpt = jobRepository.findById(body.id());
        assertTrue(persistedOpt.isPresent());
        assertEquals("IDEM-INV-5001", persistedOpt.get().getIdempotencyKey());

        // Query via HTTP GET endpoint
        ResponseEntity<JobResponse> getResponse = restTemplate.getForEntity(baseUrl + "/" + body.id(), JobResponse.class);
        assertEquals(HttpStatus.OK, getResponse.getStatusCode());
        assertNotNull(getResponse.getBody());
        assertEquals(body.id(), getResponse.getBody().id());
        assertEquals("GENERATE_INVOICE", getResponse.getBody().jobType());
    }

    @Test
    @DisplayName("Concurrent Duplicate Submission - Proves service/database-level concurrency safety (10 concurrent submissions -> 1 success, 9 DuplicateJobExceptions, 1 persisted row)")
    void testConcurrentDuplicateSubmission_DatabaseSafety() throws InterruptedException {
        int numberOfThreads = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numberOfThreads);

        String sharedIdempotencyKey = "CONCURRENT-IDEM-KEY-" + UUID.randomUUID();
        CreateJobRequest request = new CreateJobRequest(
                "SYNC_DATA",
                "{\"sync\":true}",
                Instant.now().plusSeconds(600),
                sharedIdempotencyKey
        );

        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
        List<CreateJobResponse> successfulResponses = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < numberOfThreads; i++) {
                executorService.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        CreateJobResponse response = jobSubmissionService.createJob(request);
                        successfulResponses.add(response);
                    } catch (Exception ex) {
                        exceptions.add(ex);
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            readyLatch.await();
            startLatch.countDown(); // Fire all 10 threads simultaneously
            finishLatch.await();
        } finally {
            executorService.shutdown();
        }

        // Verify service/database level concurrency contract
        assertEquals(1, successfulResponses.size(), "Exactly 1 concurrent submission must succeed");
        assertEquals(numberOfThreads - 1, exceptions.size(), "Remaining 9 concurrent submissions must fail");

        for (Exception ex : exceptions) {
            assertTrue(ex instanceof DuplicateJobException, "Exception must be DuplicateJobException, but was: " + ex.getClass().getName());
        }

        // Verify exact persistence count in PostgreSQL jobs table
        assertEquals(1, jobRepository.count(), "Real PostgreSQL database must contain exactly 1 job record");
    }
}
