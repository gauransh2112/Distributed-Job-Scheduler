package com.scheduler.job.integration;

import com.scheduler.job.config.SchedulerProperties;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.QueuePublishException;
import com.scheduler.job.queue.QueuePublisher;
import com.scheduler.job.repository.JobRepository;
import com.scheduler.job.service.JobClaimService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
class JobClaimServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test_scheduler_claim_svc_db")
            .withUsername("test_user")
            .withPassword("test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobClaimService jobClaimService;

    @MockBean
    private QueuePublisher queuePublisher;

    @AfterEach
    void tearDown() {
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("claimJobs Integration - Successfully claims due jobs and persists CLAIMED status in real PostgreSQL")
    void testClaimJobs_RealPostgresPersistence() {
        Instant now = Instant.now();
        JobEntity j1 = saveJob("SEND_EMAIL", JobStatus.PENDING, now.minusSeconds(10), "IDEM-CLAIM-1");
        JobEntity j2 = saveJob("CLEANUP", JobStatus.PENDING, now.minusSeconds(5), "IDEM-CLAIM-2");

        List<JobEntity> claimedList = jobClaimService.claimJobs(10, "node-integration-1");

        assertEquals(2, claimedList.size());
        verify(queuePublisher).publish(claimedList.get(0));
        verify(queuePublisher).publish(claimedList.get(1));

        // Verify state persistence in real PostgreSQL database
        Optional<JobEntity> updated1 = jobRepository.findById(j1.getId());
        assertTrue(updated1.isPresent());
        assertEquals(JobStatus.CLAIMED, updated1.get().getStatus());
        assertEquals("node-integration-1", updated1.get().getClaimedBy());

        Optional<JobEntity> updated2 = jobRepository.findById(j2.getId());
        assertTrue(updated2.isPresent());
        assertEquals(JobStatus.CLAIMED, updated2.get().getStatus());
        assertEquals("node-integration-1", updated2.get().getClaimedBy());
    }

    @Test
    @DisplayName("claimJobs Integration - Queue failure rolls back transaction; job remains PENDING in real PostgreSQL")
    void testClaimJobs_QueueFailure_RollsBackPostgresState() {
        Instant now = Instant.now();
        JobEntity j1 = saveJob("FAIL_QUEUE", JobStatus.PENDING, now.minusSeconds(10), "IDEM-ROLLBACK-1");

        doThrow(new QueuePublishException("Simulated SQS publish failure"))
                .when(queuePublisher).publish(any());

        assertThrows(QueuePublishException.class, () -> jobClaimService.claimJobs(5, "node-fail"));

        // Verify transaction rollback in real PostgreSQL database
        Optional<JobEntity> rolledBack = jobRepository.findById(j1.getId());
        assertTrue(rolledBack.isPresent());
        assertEquals(JobStatus.PENDING, rolledBack.get().getStatus(), "Job status must remain PENDING after rollback");
    }

    private JobEntity saveJob(String type, JobStatus status, Instant scheduledAt, String idempotencyKey) {
        JobEntity entity = JobEntity.builder()
                .id(UUID.randomUUID())
                .jobType(type)
                .payload("{\"test\":true}")
                .status(status)
                .scheduledAt(scheduledAt)
                .retryCount(0)
                .maxRetries(5)
                .idempotencyKey(idempotencyKey)
                .build();
        return jobRepository.save(entity);
    }
}
