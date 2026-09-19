package com.scheduler.job.worker;

import com.scheduler.job.config.SchedulerProperties;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.HandlerNotFoundException;
import com.scheduler.job.exception.InvalidStateTransitionException;
import com.scheduler.job.exception.JobNotFoundException;
import com.scheduler.job.handler.JobHandler;
import com.scheduler.job.handler.HandlerRegistry;
import com.scheduler.job.queue.JobMessage;
import com.scheduler.job.queue.QueueConsumer;
import com.scheduler.job.queue.ReceivedJobMessage;
import com.scheduler.job.service.JobStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Consumes job messages from the queue and drives a job through execution.
 *
 * <p>Implements the flow defined in Sequence_flows.md Flow 3:
 * receive, deserialize, {@code markRunning}, resolve handler, execute, {@code markSucceeded},
 * acknowledge. The lock release step of that flow is Phase 3, and the failure branch into
 * {@code RetryService} is Task 8; neither is implemented here.
 *
 * <p><strong>The execution-ownership gate.</strong> SQS delivers at least once, so two workers can hold
 * the same message at the same time. {@code CLAIMED -> RUNNING} is what decides which of them may run
 * the handler: it is a single conditional UPDATE, so exactly one worker wins and every other worker is
 * rejected with {@link InvalidStateTransitionException} and returns without executing anything. This is
 * the only thing standing between at-least-once delivery and duplicate execution — the visibility
 * timeout narrows the window but cannot close it.
 *
 * <p><strong>Transaction boundaries.</strong> Three separate short transactions, none of them spanning
 * the handler:
 * <ol>
 *   <li>{@code markRunning} — commits before the handler starts.</li>
 *   <li>the handler runs with no transaction open, because it may be slow and holding one would pin a
 *       connection and a row lock for the whole duration.</li>
 *   <li>{@code markSucceeded} or {@code markFailed} — commits after the handler returns.</li>
 * </ol>
 * PostgreSQL and SQS are separate systems with no shared transaction, so the acknowledgement after
 * step 3 cannot be atomic with it.
 *
 * <p><strong>Crash windows.</strong> Each gap between those steps leaves a specific, known state:
 * <ul>
 *   <li>Crash before {@code markRunning} commits: the row stays {@code CLAIMED} and the message
 *       redelivers, so another worker can take it.</li>
 *   <li>Crash after {@code markRunning} commits: the row stays {@code RUNNING} with nobody running it.
 *       Task 7 does not recover that; stale-RUNNING recovery is Phase 3.</li>
 *   <li>Crash after the handler succeeds but before {@code markSucceeded}: the row stays {@code RUNNING}
 *       and the job is <em>not</em> recorded as succeeded, which is the honest outcome — the worker
 *       cannot know the work finished. The side effects happened, so a later redelivery could repeat
 *       them; that is why handlers are required to tolerate running more than once.</li>
 *   <li>Crash after {@code markSucceeded} but before the acknowledgement: the message redelivers, the
 *       gate sees {@code SUCCEEDED} rather than {@code CLAIMED}, and the duplicate is rejected without
 *       executing the handler again. That delivery is then acknowledged, because the job is durably
 *       complete and leaving the message on the queue would loop it forever.</li>
 * </ul>
 *
 * <p><strong>Why a rejected gate is not one single outcome.</strong> The reason the gate was refused
 * decides what happens to the message, so the worker branches on the durable status it was refused
 * against:
 * <ul>
 *   <li>{@code SUCCEEDED} — the job is finished and nothing can change that. The delivery is a leftover
 *       from the window between {@code markSucceeded} committing and the acknowledgement. Executing is
 *       wrong and keeping it is worse, so it is acknowledged and discarded: at-least-once delivery may
 *       produce duplicates, but once PostgreSQL records {@code SUCCEEDED} a duplicate must become a
 *       harmless no-op.</li>
 *   <li>{@code CLAIMED} or {@code RUNNING} — another worker may still own execution right now.
 *       Acknowledging would delete the message out from under it and remove the redelivery that
 *       recovers the job if that worker dies, so this delivery is left alone.</li>
 *   <li>Anything else ({@code PENDING}, {@code FAILED}, {@code DEAD_LETTERED}) — not this task's call.
 *       The message is left on the queue for the retry policy to decide on.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final QueueConsumer queueConsumer;
    private final JobStateService jobStateService;
    private final HandlerRegistry handlerRegistry;
    private final SchedulerProperties schedulerProperties;

    public JobWorker(QueueConsumer queueConsumer,
                     JobStateService jobStateService,
                     HandlerRegistry handlerRegistry,
                     SchedulerProperties schedulerProperties) {
        this.queueConsumer = Objects.requireNonNull(queueConsumer, "queueConsumer must not be null");
        this.jobStateService = Objects.requireNonNull(jobStateService, "jobStateService must not be null");
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry, "handlerRegistry must not be null");
        this.schedulerProperties = Objects.requireNonNull(schedulerProperties,
                "schedulerProperties must not be null");
    }

    /**
     * Receives a batch of messages and processes each one.
     *
     * <p>A failure while receiving is logged and contained so the loop survives to poll again, matching
     * how the scheduler treats a failing poll. Each message is processed independently, so one bad
     * message cannot stop the rest of the batch.
     */
    @Scheduled(fixedDelayString = "${worker.poll-interval-ms}")
    public void pollAndProcess() {
        List<ReceivedJobMessage> messages;
        try {
            messages = queueConsumer.consume();
        } catch (RuntimeException e) {
            log.error("Worker poll failed to receive messages; will retry on the next poll: instance_id={}",
                    schedulerProperties.getInstanceId(), e);
            return;
        }

        for (ReceivedJobMessage message : messages) {
            try {
                process(message);
            } catch (RuntimeException e) {
                // process() handles its own outcomes; this is a backstop so one message cannot
                // abandon the rest of the batch.
                log.error("Unexpected failure processing message: sqs_message_id={}, instance_id={}",
                        message.messageId(), schedulerProperties.getInstanceId(), e);
            }
        }
    }

    /**
     * Drives one message through the execution lifecycle.
     *
     * <p>Returns normally whatever the outcome. Every path that does not complete the job leaves the
     * message unacknowledged, so it becomes visible again rather than being silently discarded.
     *
     * @param received the message to process
     */
    public void process(ReceivedJobMessage received) {
        Objects.requireNonNull(received, "received must not be null");
        JobMessage message = received.message();
        UUID jobId = message.jobId();
        String instanceId = schedulerProperties.getInstanceId();

        // Step 1: the ownership gate. Short transaction, committed before any work starts.
        try {
            jobStateService.markRunning(jobId);
        } catch (InvalidStateTransitionException e) {
            // Rejected: another worker owns this job, or the row is not in a state that may execute.
            // The handler never runs; what happens to the message depends on which state refused it.
            handleRejectedGate(received, message, instanceId, e.getCurrentStatus());
            return;
        } catch (JobNotFoundException e) {
            // The row is gone, so there is nothing to execute and nothing to reconcile against. The
            // message is left on the queue rather than discarded on this worker's initiative.
            log.warn("Rejected message: job no longer exists, handler will not run. job_id={}, "
                            + "job_type={}, trace_id={}, instance_id={}, sqs_message_id={}",
                    jobId, message.jobType(), message.traceId(), instanceId, received.messageId());
            return;
        }

        // Step 2: resolve the handler. Ordered after the gate to match Flow 3.
        JobHandler handler;
        try {
            handler = handlerRegistry.resolve(message.jobType());
        } catch (HandlerNotFoundException e) {
            log.error("No handler for job type; nothing executed. job_id={}, job_type={}, trace_id={}, "
                            + "instance_id={}", jobId, message.jobType(), message.traceId(), instanceId, e);
            recordFailure(jobId, message, instanceId, e);
            return;
        }

        // Step 3: execute the handler with no transaction open.
        long startedAt = System.nanoTime();
        try {
            handler.execute(message.payload());
        } catch (RuntimeException e) {
            log.error("Handler failed. job_id={}, job_type={}, trace_id={}, retry_count={}, "
                            + "instance_id={}, execution_time_ms={}",
                    jobId, message.jobType(), message.traceId(), message.retryCount(), instanceId,
                    elapsedMs(startedAt), e);
            recordFailure(jobId, message, instanceId, e);
            return;
        }
        long executionTimeMs = elapsedMs(startedAt);

        // Step 4: record success. Short transaction.
        jobStateService.markSucceeded(jobId);
        log.info("Job executed successfully. job_id={}, job_type={}, trace_id={}, instance_id={}, "
                        + "execution_time_ms={}",
                jobId, message.jobType(), message.traceId(), instanceId, executionTimeMs);

        // Step 5: acknowledge. The job is already durably SUCCEEDED, so a failure here must not undo it.
        acknowledgeCompleted(received, message, instanceId, "completed by this worker");
    }

    /**
     * Decides what becomes of a delivery whose execution gate was refused.
     *
     * <p>The handler never runs on any of these paths. Only a job that PostgreSQL already records as
     * {@code SUCCEEDED} has its delivery acknowledged; every other status leaves the message on the
     * queue, because either another worker may still own it or the decision belongs to the retry policy.
     */
    private void handleRejectedGate(ReceivedJobMessage received, JobMessage message, String instanceId,
                                    JobStatus currentStatus) {
        if (currentStatus == JobStatus.SUCCEEDED) {
            log.info("Discarding a duplicate delivery for an already completed job; handler not run. "
                            + "job_id={}, job_type={}, trace_id={}, instance_id={}, sqs_message_id={}",
                    message.jobId(), message.jobType(), message.traceId(), instanceId,
                    received.messageId());
            acknowledgeCompleted(received, message, instanceId, "already completed by another delivery");
            return;
        }

        log.warn("Rejected message: job is not claimable for execution, handler will not run and the "
                        + "message is left on the queue. job_id={}, job_type={}, current_status={}, "
                        + "trace_id={}, instance_id={}, sqs_message_id={}",
                message.jobId(), message.jobType(), currentStatus, message.traceId(), instanceId,
                received.messageId());
    }

    /**
     * Acknowledges a delivery whose job is durably complete.
     *
     * <p>A failure here is logged and neither rethrown nor compensated. The database is committed and
     * correct; only the queue copy of the message survives, and when it redelivers the gate rejects it
     * against {@code SUCCEEDED} and this same path acknowledges it again.
     */
    private void acknowledgeCompleted(ReceivedJobMessage received, JobMessage message, String instanceId,
                                      String reason) {
        try {
            queueConsumer.acknowledge(received);
        } catch (RuntimeException e) {
            log.error("Job is complete ({}) but its message could not be acknowledged; the redelivery "
                            + "will be rejected by the state gate and acknowledged then. job_id={}, "
                            + "trace_id={}, instance_id={}, sqs_message_id={}",
                    reason, message.jobId(), message.traceId(), instanceId, received.messageId(), e);
        }
    }

    /**
     * Records a failed execution as the documented pre-retry state.
     *
     * <p>Sets {@code RUNNING -> FAILED} with the error preserved, and stops there. Whether the job is
     * retried, backed off, or dead lettered is decided by the retry policy in a later task; this method
     * deliberately does not increment the retry count, schedule anything, or acknowledge the message.
     */
    private void recordFailure(UUID jobId, JobMessage message, String instanceId, RuntimeException cause) {
        try {
            jobStateService.markFailed(jobId, cause.getMessage());
        } catch (RuntimeException e) {
            log.error("Could not record failure for job; it remains RUNNING. job_id={}, trace_id={}, "
                    + "instance_id={}", jobId, message.traceId(), instanceId, e);
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
