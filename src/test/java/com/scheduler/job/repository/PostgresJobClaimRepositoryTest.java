package com.scheduler.job.repository;

import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.integration.DockerAvailableCondition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
class PostgresJobClaimRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test_scheduler_claim_db")
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
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("FOR UPDATE SKIP LOCKED - Proves concurrent claimers skip locked rows without blocking")
    void testForUpdateSkipLocked_ConcurrentClaimers() throws Exception {
        Instant now = Instant.now();

        // Seed 4 due PENDING jobs
        JobEntity j1 = saveJob("TYPE_A", JobStatus.PENDING, now.minusSeconds(10), "KEY-1");
        JobEntity j2 = saveJob("TYPE_A", JobStatus.PENDING, now.minusSeconds(8), "KEY-2");
        JobEntity j3 = saveJob("TYPE_A", JobStatus.PENDING, now.minusSeconds(6), "KEY-3");
        JobEntity j4 = saveJob("TYPE_A", JobStatus.PENDING, now.minusSeconds(4), "KEY-4");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch tx1AcquiredLatch = new CountDownLatch(1);
        CountDownLatch tx2CanFinishLatch = new CountDownLatch(1);

        TransactionTemplate txTemplate1 = new TransactionTemplate(transactionManager);
        TransactionTemplate txTemplate2 = new TransactionTemplate(transactionManager);

        // Thread 1: Acquire lock on batch size = 2 (should lock J1, J2) and hold transaction open
        Future<List<UUID>> future1 = executor.submit(() -> txTemplate1.execute(status -> {
            List<JobEntity> claimedBy1 = jobRepository.findEligibleJobsForClaim(now.plusSeconds(1), 2);
            tx1AcquiredLatch.countDown();
            try {
                tx2CanFinishLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return claimedBy1.stream().map(JobEntity::getId).toList();
        }));

        // Wait until Thread 1 has acquired locks inside Transaction 1
        assertTrue(tx1AcquiredLatch.await(5, TimeUnit.SECONDS), "Transaction 1 must acquire locks");

        // Thread 2: Run concurrent claim query (batch size = 2) while Transaction 1 holds locks on J1, J2
        Future<List<UUID>> future2 = executor.submit(() -> txTemplate2.execute(status -> {
            List<JobEntity> claimedBy2 = jobRepository.findEligibleJobsForClaim(now.plusSeconds(1), 2);
            return claimedBy2.stream().map(JobEntity::getId).toList();
        }));

        List<UUID> result2 = future2.get(5, TimeUnit.SECONDS);
        tx2CanFinishLatch.countDown();
        List<UUID> result1 = future1.get(5, TimeUnit.SECONDS);

        executor.shutdown();

        // Verify Thread 1 claimed J1 and J2
        assertEquals(2, result1.size(), "Claimer 1 must receive exactly 2 jobs");
        assertTrue(result1.contains(j1.getId()), "Claimer 1 must include Job 1");
        assertTrue(result1.contains(j2.getId()), "Claimer 1 must include Job 2");

        // Verify Thread 2 skipped locked J1/J2 and claimed J3 and J4 without blocking
        assertEquals(2, result2.size(), "Claimer 2 must receive exactly 2 jobs");
        assertTrue(result2.contains(j3.getId()), "Claimer 2 must skip locked rows and claim Job 3");
        assertTrue(result2.contains(j4.getId()), "Claimer 2 must skip locked rows and claim Job 4");

        // Assert zero overlap between claimer results
        assertTrue(Collections.disjoint(result1, result2), "Claimers must not be returned any duplicate jobs");
    }

    @Test
    @DisplayName("Status & Timing Filtering - Only due PENDING jobs are returned")
    void testFindEligibleJobsForClaim_StatusAndTimingFiltering() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();

            // Eligible: PENDING and scheduled in past
            JobEntity eligible = saveJob("TEST", JobStatus.PENDING, now.minusSeconds(10), "KEY-ELIGIBLE");

            // Ineligible jobs
            saveJob("TEST", JobStatus.PENDING, now.plusSeconds(600), "KEY-FUTURE");
            saveJob("TEST", JobStatus.CLAIMED, now.minusSeconds(10), "KEY-CLAIMED");
            saveJob("TEST", JobStatus.RUNNING, now.minusSeconds(10), "KEY-RUNNING");
            saveJob("TEST", JobStatus.SUCCEEDED, now.minusSeconds(10), "KEY-SUCCEEDED");
            saveJob("TEST", JobStatus.FAILED, now.minusSeconds(10), "KEY-FAILED");
            saveJob("TEST", JobStatus.DEAD_LETTERED, now.minusSeconds(10), "KEY-DLQ");

            List<JobEntity> results = jobRepository.findEligibleJobsForClaim(now, 10);

            assertEquals(1, results.size(), "Only eligible due PENDING job must be returned");
            assertEquals(eligible.getId(), results.get(0).getId());
        });
    }

    @Test
    @DisplayName("Batch Size Limit & Ordering - Respects batch limit and orders by scheduledAt ASC")
    void testFindEligibleJobsForClaim_BatchLimitAndOrdering() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();

            JobEntity oldest = saveJob("TEST", JobStatus.PENDING, now.minusSeconds(30), "KEY-1");
            JobEntity middle = saveJob("TEST", JobStatus.PENDING, now.minusSeconds(20), "KEY-2");
            saveJob("TEST", JobStatus.PENDING, now.minusSeconds(10), "KEY-3");

            List<JobEntity> results = jobRepository.findEligibleJobsForClaim(now, 2);

            assertEquals(2, results.size(), "Batch limit of 2 must be respected");
            assertEquals(oldest.getId(), results.get(0).getId(), "First job must be oldest scheduledAt");
            assertEquals(middle.getId(), results.get(1).getId(), "Second job must be second oldest scheduledAt");
        });
    }

    @Test
    @DisplayName("Future Jobs - Future scheduled jobs are not claimed")
    void testFindEligibleJobsForClaim_FutureJobsNotClaimed() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            saveJob("TEST", JobStatus.PENDING, now.plusSeconds(300), "KEY-FUTURE-ONLY");

            List<JobEntity> results = jobRepository.findEligibleJobsForClaim(now, 10);

            assertTrue(results.isEmpty(), "Future jobs must not be claimed");
        });
    }

    private JobEntity saveJob(String type, JobStatus status, Instant scheduledAt, String idempotencyKey) {
        JobEntity entity = JobEntity.builder()
                .id(UUID.randomUUID())
                .jobType(type)
                .payload("{\"key\":\"value\"}")
                .status(status)
                .scheduledAt(scheduledAt)
                .retryCount(0)
                .maxRetries(5)
                .idempotencyKey(idempotencyKey)
                .build();
        return jobRepository.save(entity);
    }
}
