package com.scheduler.job.integration;

import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.InvalidStateTransitionException;
import com.scheduler.job.repository.JobRepository;
import com.scheduler.job.service.JobStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the execution-ownership gate against real PostgreSQL.
 *
 * <p>{@code CLAIMED -> RUNNING} is the single point that decides which worker may execute a job. SQS
 * delivers at least once, so two workers can hold the same message at the same time; if both can win
 * this transition, both run the handler. Task 2's {@code FOR UPDATE SKIP LOCKED} does not help here,
 * because it guards {@code PENDING -> CLAIMED} only.
 *
 * <p>Mocking the repository would prove nothing: the question is what two real transactions do against
 * a real database under its actual isolation level, so this runs concurrent threads against real
 * PostgreSQL.
 */
@SpringBootTest
@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
class MarkRunningConcurrencyTest {

    private static final int CONCURRENT_WORKERS = 8;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test_mark_running_db")
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
    private JobStateService jobStateService;

    @AfterEach
    void tearDown() {
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("Exactly one concurrent worker may win CLAIMED -> RUNNING for the same job")
    void testOnlyOneWorkerWinsTheExecutionGate() throws Exception {
        JobEntity job = saveClaimedJob();

        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_WORKERS);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < CONCURRENT_WORKERS; i++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    startLine.await();
                    try {
                        jobStateService.markRunning(job.getId());
                        winners.incrementAndGet();
                    } catch (InvalidStateTransitionException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }

            startLine.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, winners.get(),
                "Exactly one worker may win the execution gate; " + winners.get()
                        + " won, which would mean the handler runs " + winners.get() + " times");
        assertEquals(CONCURRENT_WORKERS - 1, rejected.get(),
                "Every loser must be rejected by the state transition");
        assertEquals(JobStatus.RUNNING, jobRepository.findById(job.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("A job that is not CLAIMED cannot be transitioned to RUNNING")
    void testNonClaimedJobRejected() {
        for (JobStatus status : List.of(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.SUCCEEDED,
                JobStatus.FAILED, JobStatus.DEAD_LETTERED)) {
            JobEntity job = saveJob(status);
            org.junit.jupiter.api.Assertions.assertThrows(InvalidStateTransitionException.class,
                    () -> jobStateService.markRunning(job.getId()),
                    "A job in " + status + " must not be transitioned to RUNNING");
            assertEquals(status, jobRepository.findById(job.getId()).orElseThrow().getStatus(),
                    "A rejected transition must not modify the row");
        }
    }

    private JobEntity saveClaimedJob() {
        return saveJob(JobStatus.CLAIMED);
    }

    private JobEntity saveJob(JobStatus status) {
        return jobRepository.save(JobEntity.builder()
                .id(UUID.randomUUID())
                .jobType("SEND_EMAIL")
                .payload("{\"to\":\"user@example.com\",\"template\":\"WELCOME_EMAIL\"}")
                .status(status)
                .scheduledAt(Instant.now())
                .retryCount(0)
                .maxRetries(5)
                .claimedBy("scheduler-1")
                .idempotencyKey("IDEM-" + UUID.randomUUID())
                .build());
    }
}
