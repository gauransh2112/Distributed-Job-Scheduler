package com.scheduler.job.integration;

import com.scheduler.job.config.SchedulerProperties;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.JobExecutionException;
import com.scheduler.job.exception.QueueConsumeException;
import com.scheduler.job.handler.HandlerRegistry;
import com.scheduler.job.handler.JobHandler;
import com.scheduler.job.queue.QueueConsumer;
import com.scheduler.job.queue.QueuePublisher;
import com.scheduler.job.queue.ReceivedJobMessage;
import com.scheduler.job.repository.JobRepository;
import com.scheduler.job.service.JobStateService;
import com.scheduler.job.worker.JobWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end worker lifecycle against real PostgreSQL and real LocalStack SQS.
 *
 * <p>Nothing about the database or the queue is mocked. The state transitions run against real
 * PostgreSQL, and the messages are really published, received and deleted through LocalStack, because
 * the properties under test — that exactly one of two concurrent workers may execute a job, and that a
 * failed acknowledgement cannot undo a committed transition — are properties of those systems rather
 * than of the worker class.
 *
 * <p>The handler is a counting test double: what is under test is how many times the worker runs a
 * handler, not what a particular handler does. The {@link HandlerRegistry} around it is the real one.
 */
@SpringBootTest
@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
class JobWorkerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test_worker_db")
            .withUsername("test_user")
            .withPassword("test_pass");

    @Container
    static final LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.4.0"))
            .withServices(LocalStackContainer.Service.SQS);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");

        registry.add("aws.region", localstack::getRegion);
        registry.add("aws.access-key-id", localstack::getAccessKey);
        registry.add("aws.secret-access-key", localstack::getSecretKey);
        registry.add("aws.sqs.endpoint", () -> localstack.getEndpoint().toString());
        registry.add("aws.sqs.queue-name", () -> "worker-it-jobs");
        registry.add("aws.sqs.dlq-name", () -> "worker-it-jobs-dlq");
        registry.add("aws.sqs.auto-create-queues", () -> "true");
        registry.add("aws.sqs.visibility-timeout-seconds", () -> "2");
        registry.add("aws.sqs.wait-time-seconds", () -> "1");
    }

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobStateService jobStateService;

    @Autowired
    private QueuePublisher queuePublisher;

    @Autowired
    private QueueConsumer queueConsumer;

    private final AtomicInteger executions = new AtomicInteger();

    @AfterEach
    void tearDown() {
        drainQueue();
        jobRepository.deleteAll();
        executions.set(0);
    }

    @Test
    @DisplayName("A claimed job runs end to end: CLAIMED -> RUNNING -> SUCCEEDED, then the message is acknowledged")
    void testSuccessfulLifecycle() {
        JobEntity job = saveJob(JobStatus.CLAIMED);
        queuePublisher.publish(job);
        ReceivedJobMessage received = receiveOne();

        worker(countingHandler()).process(received);

        assertEquals(1, executions.get(), "The handler must run exactly once");
        assertEquals(JobStatus.SUCCEEDED, statusOf(job));
        // Acknowledged means gone: nothing comes back when the visibility window elapses.
        assertTrue(receiveNothingFor(Duration.ofSeconds(6)),
                "A completed job's message must not be redelivered");
    }

    @Test
    @DisplayName("Two concurrent workers holding the same delivery execute the handler exactly once")
    void testConcurrentDuplicateDeliveryExecutesHandlerOnce() throws Exception {
        JobEntity job = saveJob(JobStatus.CLAIMED);
        queuePublisher.publish(job);
        ReceivedJobMessage received = receiveOne();

        JobWorker workerOne = worker(countingHandler());
        JobWorker workerTwo = worker(countingHandler());

        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (JobWorker worker : List.of(workerOne, workerTwo)) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    startLine.await();
                    worker.process(received);
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

        assertEquals(1, executions.get(),
                "At-least-once delivery must not become at-least-once execution: the losing worker's "
                        + "CLAIMED -> RUNNING transition has to reject it");
        assertEquals(JobStatus.SUCCEEDED, statusOf(job));
    }

    @Test
    @DisplayName("Re-processing an already completed job does not execute the handler again")
    void testRedeliveryAfterSuccessIsRejected() {
        JobEntity job = saveJob(JobStatus.CLAIMED);
        queuePublisher.publish(job);
        ReceivedJobMessage received = receiveOne();
        JobWorker worker = worker(countingHandler());

        worker.process(received);
        // Stands in for the crash window between markSucceeded and the acknowledgement: the same
        // message arrives again after the job is already SUCCEEDED.
        worker.process(received);

        assertEquals(1, executions.get());
        assertEquals(JobStatus.SUCCEEDED, statusOf(job));
    }

    @Test
    @DisplayName("Crash between markSucceeded and acknowledgement: the redelivery is a no-op and is acknowledged")
    void testCrashAfterSuccessBeforeAcknowledgementDoesNotLoopForever() {
        JobEntity job = saveJob(JobStatus.CLAIMED);
        queuePublisher.publish(job);

        // Reproduce the crash window without a crash: the job is driven to SUCCEEDED in PostgreSQL
        // through the real transitions, and the delivery is deliberately never acknowledged, exactly
        // as if the process had died between markSucceeded committing and the delete call.
        ReceivedJobMessage firstDelivery = receiveOne();
        jobStateService.markRunning(job.getId());
        jobStateService.markSucceeded(job.getId());
        assertEquals(JobStatus.SUCCEEDED, statusOf(job));
        assertEquals(0, executions.get());

        // SQS makes the unacknowledged message visible again once the visibility timeout expires.
        ReceivedJobMessage redelivered = receiveOneAfterVisibilityWindow();
        assertNotNull(redelivered, "The unacknowledged message must redeliver");
        assertEquals(firstDelivery.message().jobId(), redelivered.message().jobId());

        worker(countingHandler()).process(redelivered);

        assertEquals(0, executions.get(),
                "A duplicate delivery for a completed job must never execute the handler");
        assertEquals(JobStatus.SUCCEEDED, statusOf(job), "The completed state must be left alone");
        // The decisive assertion: the message is gone rather than cycling back forever.
        assertTrue(receiveNothingFor(Duration.ofSeconds(8)),
                "A duplicate delivery for a SUCCEEDED job must be acknowledged, not left to loop");
    }

    @Test
    @DisplayName("A delivery refused against RUNNING is left on the queue for its owning worker")
    void testDeliveryRefusedAgainstRunningIsLeftOnTheQueue() {
        JobEntity job = saveJob(JobStatus.CLAIMED);
        queuePublisher.publish(job);
        ReceivedJobMessage received = receiveOne();

        // Another worker owns execution right now: the row is RUNNING and not yet finished.
        jobStateService.markRunning(job.getId());

        worker(countingHandler()).process(received);

        assertEquals(0, executions.get());
        assertEquals(JobStatus.RUNNING, statusOf(job));
        // Deleting this message would remove the redelivery that recovers the job if the owning
        // worker dies, so it must still be there.
        assertNotNull(receiveOneAfterVisibilityWindow(),
                "A delivery refused against RUNNING must not be acknowledged");
    }

    @Test
    @DisplayName("A message whose job is not CLAIMED executes nothing and is left on the queue")
    void testJobNotClaimedIsNotExecuted() {
        // A stale message: the claim transaction rolled back, so the row is still PENDING.
        JobEntity job = saveJob(JobStatus.PENDING);
        queuePublisher.publish(job);
        ReceivedJobMessage received = receiveOne();

        worker(countingHandler()).process(received);

        assertEquals(0, executions.get(), "A job that is not CLAIMED must never be executed");
        assertEquals(JobStatus.PENDING, statusOf(job), "The row must be left untouched");
        assertNotNull(receiveOneAfterVisibilityWindow(),
                "An unacknowledged message must return to the queue");
    }

    @Test
    @DisplayName("A handler failure records FAILED with the error and leaves the message unacknowledged")
    void testHandlerFailureRecordsPreRetryState() {
        JobEntity job = saveJob(JobStatus.CLAIMED);
        queuePublisher.publish(job);
        ReceivedJobMessage received = receiveOne();

        worker(failingHandler("boom from handler")).process(received);

        JobEntity stored = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.FAILED, stored.getStatus(),
                "A failed execution belongs in the documented pre-retry state");
        assertEquals("boom from handler", stored.getLastError());
        assertEquals(0, stored.getRetryCount(), "Incrementing the retry count is the retry policy's job");
        assertNotNull(receiveOneAfterVisibilityWindow(),
                "The message must remain for the retry policy to decide on");
    }

    @Test
    @DisplayName("An unknown job type executes nothing")
    void testUnknownJobTypeExecutesNothing() {
        JobEntity job = saveJob(JobStatus.CLAIMED, "NO_SUCH_JOB_TYPE");
        queuePublisher.publish(job);
        ReceivedJobMessage received = receiveOne();

        worker(countingHandler()).process(received);

        assertEquals(0, executions.get());
        assertEquals(JobStatus.FAILED, statusOf(job));
    }

    @Test
    @DisplayName("A failed acknowledgement does not revert the committed SUCCEEDED state")
    void testAcknowledgementFailureDoesNotRevertState() {
        JobEntity job = saveJob(JobStatus.CLAIMED);
        queuePublisher.publish(job);
        ReceivedJobMessage received = receiveOne();

        QueueConsumer failingAcknowledger = new QueueConsumer() {
            @Override
            public List<ReceivedJobMessage> consume() {
                return queueConsumer.consume();
            }

            @Override
            public void acknowledge(ReceivedJobMessage message) {
                throw new QueueConsumeException("simulated delete failure");
            }
        };

        new JobWorker(failingAcknowledger, jobStateService, new HandlerRegistry(List.of(countingHandler())),
                new SchedulerProperties(5000, 50, "worker-it")).process(received);

        assertEquals(1, executions.get());
        assertEquals(JobStatus.SUCCEEDED, statusOf(job),
                "The database is committed and correct; a queue failure must not undo it");
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private JobWorker worker(JobHandler handler) {
        return new JobWorker(queueConsumer, jobStateService, new HandlerRegistry(List.of(handler)),
                new SchedulerProperties(5000, 50, "worker-it"));
    }

    private JobHandler countingHandler() {
        return handler("SEND_EMAIL", payload -> executions.incrementAndGet());
    }

    private JobHandler failingHandler(String message) {
        return handler("SEND_EMAIL", payload -> {
            executions.incrementAndGet();
            throw new JobExecutionException(message);
        });
    }

    private JobHandler handler(String jobType, java.util.function.Consumer<String> body) {
        return new JobHandler() {
            @Override
            public String jobType() {
                return jobType;
            }

            @Override
            public void execute(String payload) {
                body.accept(payload);
            }
        };
    }

    private ReceivedJobMessage receiveOne() {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            List<ReceivedJobMessage> messages = queueConsumer.consume();
            if (!messages.isEmpty()) {
                return messages.get(0);
            }
        }
        throw new IllegalStateException("No message received within the timeout");
    }

    private ReceivedJobMessage receiveOneAfterVisibilityWindow() {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            List<ReceivedJobMessage> messages = queueConsumer.consume();
            if (!messages.isEmpty()) {
                return messages.get(0);
            }
        }
        return null;
    }

    private boolean receiveNothingFor(Duration window) {
        Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            if (!queueConsumer.consume().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void drainQueue() {
        Instant deadline = Instant.now().plusSeconds(8);
        while (Instant.now().isBefore(deadline)) {
            List<ReceivedJobMessage> messages = queueConsumer.consume();
            if (messages.isEmpty()) {
                continue;
            }
            messages.forEach(queueConsumer::acknowledge);
        }
    }

    private JobStatus statusOf(JobEntity job) {
        return jobRepository.findById(job.getId()).orElseThrow().getStatus();
    }

    private JobEntity saveJob(JobStatus status) {
        return saveJob(status, "SEND_EMAIL");
    }

    private JobEntity saveJob(JobStatus status, String jobType) {
        return jobRepository.save(JobEntity.builder()
                .id(UUID.randomUUID())
                .jobType(jobType)
                .payload("{\"to\":\"user@example.com\",\"template\":\"WELCOME_EMAIL\"}")
                .status(status)
                .scheduledAt(Instant.now())
                .retryCount(0)
                .maxRetries(5)
                .claimedBy("scheduler-it")
                .idempotencyKey("IDEM-" + UUID.randomUUID())
                .build());
    }
}
