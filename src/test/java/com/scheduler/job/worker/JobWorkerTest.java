package com.scheduler.job.worker;

import com.scheduler.job.config.SchedulerProperties;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.HandlerNotFoundException;
import com.scheduler.job.exception.InvalidStateTransitionException;
import com.scheduler.job.exception.JobExecutionException;
import com.scheduler.job.exception.JobNotFoundException;
import com.scheduler.job.exception.QueueConsumeException;
import com.scheduler.job.handler.HandlerRegistry;
import com.scheduler.job.handler.JobHandler;
import com.scheduler.job.queue.JobMessage;
import com.scheduler.job.queue.QueueConsumer;
import com.scheduler.job.queue.ReceivedJobMessage;
import com.scheduler.job.service.JobStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the worker's control flow: what it calls, in what order, and what it refuses to do.
 *
 * <p>Mocks are used here only to observe the worker's own decisions. Whether the ownership gate
 * actually excludes a concurrent worker is a property of PostgreSQL, not of this class, and is proven
 * against a real database in {@code MarkRunningConcurrencyTest} and {@code JobWorkerIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class JobWorkerTest {

    private static final String INSTANCE_ID = "worker-test-1";

    @Mock
    private QueueConsumer queueConsumer;

    @Mock
    private JobStateService jobStateService;

    @Mock
    private HandlerRegistry handlerRegistry;

    @Mock
    private JobHandler jobHandler;

    private JobWorker jobWorker;

    @BeforeEach
    void setUp() {
        jobWorker = new JobWorker(queueConsumer, jobStateService, handlerRegistry,
                new SchedulerProperties(5000, 50, INSTANCE_ID));
    }

    @Test
    @DisplayName("The happy path runs gate, handler, success and acknowledgement in that order")
    void testSuccessfulLifecycleOrder() {
        ReceivedJobMessage received = message(UUID.randomUUID(), "SEND_EMAIL");
        when(handlerRegistry.resolve("SEND_EMAIL")).thenReturn(jobHandler);

        jobWorker.process(received);

        InOrder order = inOrder(jobStateService, handlerRegistry, jobHandler, queueConsumer);
        order.verify(jobStateService).markRunning(received.message().jobId());
        order.verify(handlerRegistry).resolve("SEND_EMAIL");
        order.verify(jobHandler).execute(received.message().payload());
        order.verify(jobStateService).markSucceeded(received.message().jobId());
        order.verify(queueConsumer).acknowledge(received);
    }

    @Test
    @DisplayName("A worker that loses the gate does not execute the handler and does not acknowledge")
    void testLostGateDoesNotExecuteHandler() {
        ReceivedJobMessage received = message(UUID.randomUUID(), "SEND_EMAIL");
        // Another worker already took ownership, so the compare-and-swap rejected this one.
        when(jobStateService.markRunning(any(UUID.class))).thenThrow(
                new InvalidStateTransitionException(received.message().jobId(),
                        JobStatus.RUNNING, JobStatus.RUNNING));

        assertDoesNotThrow(() -> jobWorker.process(received));

        verifyNoInteractions(handlerRegistry, jobHandler);
        verify(jobStateService, never()).markSucceeded(any());
        verify(queueConsumer, never()).acknowledge(any());
    }

    @Test
    @DisplayName("A duplicate delivery for an already SUCCEEDED job is acknowledged, not left to loop")
    void testDuplicateAfterSuccessIsAcknowledged() {
        ReceivedJobMessage received = message(UUID.randomUUID(), "SEND_EMAIL");
        // The crash window: markSucceeded committed, the acknowledgement never happened, and the
        // message has now redelivered.
        when(jobStateService.markRunning(any(UUID.class))).thenThrow(
                new InvalidStateTransitionException(received.message().jobId(),
                        JobStatus.SUCCEEDED, JobStatus.RUNNING));

        jobWorker.process(received);

        verifyNoInteractions(handlerRegistry, jobHandler);
        // Acknowledged, because the job is durably complete and the message would otherwise redeliver
        // forever: the gate would refuse it on every cycle.
        verify(queueConsumer).acknowledge(received);
        verify(jobStateService, never()).markSucceeded(any());
        verify(jobStateService, never()).markFailed(any(), anyString());
    }

    @Test
    @DisplayName("A delivery refused against RUNNING is left on the queue for its owning worker")
    void testRejectedAgainstRunningIsNotAcknowledged() {
        ReceivedJobMessage received = message(UUID.randomUUID(), "SEND_EMAIL");
        when(jobStateService.markRunning(any(UUID.class))).thenThrow(
                new InvalidStateTransitionException(received.message().jobId(),
                        JobStatus.RUNNING, JobStatus.RUNNING));

        jobWorker.process(received);

        verifyNoInteractions(handlerRegistry, jobHandler);
        // Acknowledging would delete the message another worker is still working from, and remove the
        // redelivery that recovers the job if that worker dies.
        verify(queueConsumer, never()).acknowledge(any());
    }

    @Test
    @DisplayName("A delivery refused against CLAIMED is left on the queue")
    void testRejectedAgainstClaimedIsNotAcknowledged() {
        ReceivedJobMessage received = message(UUID.randomUUID(), "SEND_EMAIL");
        when(jobStateService.markRunning(any(UUID.class))).thenThrow(
                new InvalidStateTransitionException(received.message().jobId(),
                        JobStatus.CLAIMED, JobStatus.RUNNING));

        jobWorker.process(received);

        verify(queueConsumer, never()).acknowledge(any());
    }

    @Test
    @DisplayName("A delivery refused against FAILED or DEAD_LETTERED is left for the retry policy")
    void testRejectedAgainstFailedStatesIsNotAcknowledged() {
        for (JobStatus status : List.of(JobStatus.FAILED, JobStatus.DEAD_LETTERED)) {
            ReceivedJobMessage received = message(UUID.randomUUID(), "SEND_EMAIL");
            when(jobStateService.markRunning(received.message().jobId())).thenThrow(
                    new InvalidStateTransitionException(received.message().jobId(), status,
                            JobStatus.RUNNING));

            jobWorker.process(received);

            verify(queueConsumer, never()).acknowledge(received);
        }
        verifyNoInteractions(handlerRegistry, jobHandler);
    }

    @Test
    @DisplayName("An acknowledgement failure while discarding a duplicate is contained")
    void testDuplicateAcknowledgementFailureContained() {
        ReceivedJobMessage received = message(UUID.randomUUID(), "SEND_EMAIL");
        when(jobStateService.markRunning(any(UUID.class))).thenThrow(
                new InvalidStateTransitionException(received.message().jobId(),
                        JobStatus.SUCCEEDED, JobStatus.RUNNING));
        doThrow(new QueueConsumeException("delete rejected")).when(queueConsumer).acknowledge(received);

        assertDoesNotThrow(() -> jobWorker.process(received));

        verifyNoInteractions(handlerRegistry, jobHandler);
    }

    @Test
    @DisplayName("A message whose job is not CLAIMED is rejected before anything executes")
    void testNotClaimedJobRejected() {
        ReceivedJobMessage received = message(UUID.randomUUID(), "SEND_EMAIL");
        // A stale message left behind when a claim transaction rolled back: the row is still PENDING.
        when(jobStateService.markRunning(any(UUID.class))).thenThrow(
                new InvalidStateTransitionException(received.message().jobId(),
                        JobStatus.PENDING, JobStatus.RUNNING));

        jobWorker.process(received);

        verifyNoInteractions(handlerRegistry, jobHandler);
        verify(queueConsumer, never()).acknowledge(any());
    }

    @Test
    @DisplayName("A message for a job that no longer exists executes nothing")
    void testMissingJobRejected() {
        ReceivedJobMessage received = message(UUID.randomUUID(), "SEND_EMAIL");
        when(jobStateService.markRunning(any(UUID.class)))
                .thenThrow(new JobNotFoundException(received.message().jobId()));

        jobWorker.process(received);

        verifyNoInteractions(handlerRegistry, jobHandler);
        verify(queueConsumer, never()).acknowledge(any());
    }

    @Test
    @DisplayName("An unknown job type executes nothing and is recorded as failed, not succeeded")
    void testUnknownHandlerExecutesNothing() {
        ReceivedJobMessage received = message(UUID.randomUUID(), "NOT_A_JOB_TYPE");
        when(handlerRegistry.resolve("NOT_A_JOB_TYPE"))
                .thenThrow(new HandlerNotFoundException("No handler registered for job type"));

        jobWorker.process(received);

        verifyNoInteractions(jobHandler);
        verify(jobStateService, never()).markSucceeded(any());
        verify(jobStateService).markFailed(eq(received.message().jobId()), anyString());
        verify(queueConsumer, never()).acknowledge(any());
    }

    @Test
    @DisplayName("A handler failure records the pre-retry state and never marks the job succeeded")
    void testHandlerFailureRecordsPreRetryState() {
        ReceivedJobMessage received = message(UUID.randomUUID(), "SEND_EMAIL");
        when(handlerRegistry.resolve("SEND_EMAIL")).thenReturn(jobHandler);
        doThrow(new JobExecutionException("payload rejected")).when(jobHandler).execute(anyString());

        jobWorker.process(received);

        verify(jobStateService).markFailed(received.message().jobId(), "payload rejected");
        verify(jobStateService, never()).markSucceeded(any());
        // The message is left unacknowledged: what happens to a FAILED job is the retry policy's call.
        verify(queueConsumer, never()).acknowledge(any());
    }

    @Test
    @DisplayName("Acknowledgement happens only after the success transition has been recorded")
    void testAcknowledgementOnlyAfterStateCompletion() {
        ReceivedJobMessage received = message(UUID.randomUUID(), "SEND_EMAIL");
        when(handlerRegistry.resolve("SEND_EMAIL")).thenReturn(jobHandler);
        doThrow(new IllegalStateException("database unavailable"))
                .when(jobStateService).markSucceeded(any(UUID.class));

        // The success transition never committed, so the message must stay on the queue. The failure
        // surfaces rather than being swallowed; the polling loop's backstop contains it.
        assertThrows(IllegalStateException.class, () -> jobWorker.process(received));

        verify(queueConsumer, never()).acknowledge(any());
    }

    @Test
    @DisplayName("An acknowledgement failure does not undo the committed success")
    void testAcknowledgementFailureDoesNotRevertState() {
        ReceivedJobMessage received = message(UUID.randomUUID(), "SEND_EMAIL");
        when(handlerRegistry.resolve("SEND_EMAIL")).thenReturn(jobHandler);
        doThrow(new QueueConsumeException("delete rejected")).when(queueConsumer).acknowledge(received);

        assertDoesNotThrow(() -> jobWorker.process(received));

        verify(jobStateService).markSucceeded(received.message().jobId());
        // No compensating transition: the job really did succeed and must stay SUCCEEDED.
        verify(jobStateService, never()).markFailed(any(), anyString());
        verify(jobStateService, never()).markPending(any(), any());
    }

    @Test
    @DisplayName("A receive failure is contained so the polling loop survives")
    void testReceiveFailureContained() {
        when(queueConsumer.consume()).thenThrow(new QueueConsumeException("receive rejected"));

        assertDoesNotThrow(() -> jobWorker.pollAndProcess());

        verifyNoInteractions(jobStateService, handlerRegistry, jobHandler);
    }

    @Test
    @DisplayName("One failing message does not abandon the rest of the batch")
    void testOneBadMessageDoesNotStopTheBatch() {
        ReceivedJobMessage first = message(UUID.randomUUID(), "SEND_EMAIL");
        ReceivedJobMessage second = message(UUID.randomUUID(), "SEND_EMAIL");
        when(queueConsumer.consume()).thenReturn(List.of(first, second));
        when(handlerRegistry.resolve("SEND_EMAIL")).thenReturn(jobHandler);
        when(jobStateService.markRunning(first.message().jobId()))
                .thenThrow(new IllegalStateException("unexpected"));

        assertDoesNotThrow(() -> jobWorker.pollAndProcess());

        verify(jobStateService).markRunning(second.message().jobId());
        verify(jobStateService).markSucceeded(second.message().jobId());
        verify(queueConsumer).acknowledge(second);
    }

    @Test
    @DisplayName("Each message in a batch is processed")
    void testWholeBatchProcessed() {
        ReceivedJobMessage first = message(UUID.randomUUID(), "SEND_EMAIL");
        ReceivedJobMessage second = message(UUID.randomUUID(), "SEND_EMAIL");
        when(queueConsumer.consume()).thenReturn(List.of(first, second));
        when(handlerRegistry.resolve("SEND_EMAIL")).thenReturn(jobHandler);

        jobWorker.pollAndProcess();

        verify(jobHandler, times(2)).execute(anyString());
        verify(queueConsumer).acknowledge(first);
        verify(queueConsumer).acknowledge(second);
    }

    private ReceivedJobMessage message(UUID jobId, String jobType) {
        return new ReceivedJobMessage(
                "sqs-" + jobId,
                "receipt-" + jobId,
                new JobMessage(jobId, jobType, "{\"to\":\"user@example.com\",\"template\":\"WELCOME\"}",
                        0, "trace-" + jobId));
    }
}
