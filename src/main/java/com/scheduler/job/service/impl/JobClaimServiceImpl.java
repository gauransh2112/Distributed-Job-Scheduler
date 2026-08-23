package com.scheduler.job.service.impl;

import com.scheduler.job.config.SchedulerProperties;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.queue.QueuePublisher;
import com.scheduler.job.repository.JobRepository;
import com.scheduler.job.service.JobClaimService;
import com.scheduler.job.service.JobStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Production implementation of {@link JobClaimService}.
 * Coordinates atomic database claiming via PostgreSQL FOR UPDATE SKIP LOCKED,
 * updates state to CLAIMED via {@link JobStateService}, and publishes claimed jobs to SQS via {@link QueuePublisher}.
 *
 * <p><strong>Failure Analysis & Transaction Boundaries:</strong>
 * <ul>
 *   <li><strong>Single Transaction Boundary:</strong> {@code claimJobs()} runs within an active {@code @Transactional} context.
 *       Calls to {@code jobStateService.markClaimed()} inherit the outer transaction ({@code Propagation.REQUIRED}).</li>
 *   <li><strong>Queue Publication Exception:</strong> If {@code queuePublisher.publish()} throws {@code QueuePublishException},
 *       Spring marks the transaction for rollback. PostgreSQL status reverts from {@code CLAIMED} back to {@code PENDING},
 *       {@code claimedBy} reverts to {@code null}, and row locks are released.</li>
 *   <li><strong>Dual-System Non-Atomicity (SQS Publish Succeeds & DB Rolls Back):</strong> SQS and PostgreSQL do not share a 2PC transaction.
 *       If SQS receives a message but DB rolls back, the worker receiving the message calls {@code markRunning(jobId)}.
 *       Since status is {@code PENDING} (not {@code CLAIMED}), {@code markRunning} throws {@code InvalidStateTransitionException},
 *       rejecting execution. The database record remains {@code PENDING} and will be re-discovered and re-published on a subsequent scheduler poll.</li>
 *   <li><strong>Stuck CLAIMED State Failure:</strong> If a scheduler process crashes after DB commit (status = {@code CLAIMED})
 *       but before SQS delivers the message to a worker, the job remains in {@code CLAIMED} status in Phase 2.
 *       Phase 2 does not introduce a sweeper; automatic recovery of stale {@code CLAIMED} jobs via heartbeat/lock sweeper is deferred to Phase 3.</li>
 * </ul>
 */
@Service
public class JobClaimServiceImpl implements JobClaimService {

    private static final Logger log = LoggerFactory.getLogger(JobClaimServiceImpl.class);

    private final JobRepository jobRepository;
    private final JobStateService jobStateService;
    private final QueuePublisher queuePublisher;
    private final SchedulerProperties schedulerProperties;
    private final Clock clock;

    @Autowired
    public JobClaimServiceImpl(
            JobRepository jobRepository,
            JobStateService jobStateService,
            QueuePublisher queuePublisher,
            SchedulerProperties schedulerProperties) {
        this(jobRepository, jobStateService, queuePublisher, schedulerProperties, Clock.systemUTC());
    }

    public JobClaimServiceImpl(
            JobRepository jobRepository,
            JobStateService jobStateService,
            QueuePublisher queuePublisher,
            SchedulerProperties schedulerProperties,
            Clock clock) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.jobStateService = Objects.requireNonNull(jobStateService, "jobStateService must not be null");
        this.queuePublisher = Objects.requireNonNull(queuePublisher, "queuePublisher must not be null");
        this.schedulerProperties = Objects.requireNonNull(schedulerProperties, "schedulerProperties must not be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    @Transactional
    public List<JobEntity> claimJobs() {
        return claimJobs(schedulerProperties.getBatchSize(), schedulerProperties.getInstanceId());
    }

    @Override
    @Transactional
    public List<JobEntity> claimJobs(int batchSize, String instanceId) {
        // Enforce valid fallback values if callers pass non-positive batchSize or empty instanceId
        if (batchSize <= 0) {
            log.warn("Invalid batchSize ({}) passed to claimJobs; falling back to configured default ({})",
                    batchSize, schedulerProperties.getBatchSize());
            batchSize = schedulerProperties.getBatchSize();
        }
        if (instanceId == null || instanceId.isBlank()) {
            log.warn("Blank instanceId passed to claimJobs; falling back to configured default ({})",
                    schedulerProperties.getInstanceId());
            instanceId = schedulerProperties.getInstanceId();
        }

        Instant now = clock.instant();
        List<JobEntity> dueJobs = jobRepository.findEligibleJobsForClaim(now, batchSize);

        if (dueJobs.isEmpty()) {
            log.debug("No due PENDING jobs found for claiming at {}", now);
            return List.of();
        }

        log.info("Found {} due PENDING jobs for claiming by instance '{}'", dueJobs.size(), instanceId);
        List<JobEntity> claimedJobs = new ArrayList<>(dueJobs.size());

        for (JobEntity job : dueJobs) {
            // Step 1: Centralized status transition PENDING -> CLAIMED (participates in outer transaction)
            JobEntity claimed = jobStateService.markClaimed(job.getId(), instanceId);

            // Step 2: Publish to SQS execution queue
            // On failure, QueuePublishException triggers transaction rollback
            queuePublisher.publish(claimed);

            claimedJobs.add(claimed);
        }

        log.info("Successfully claimed and published {} jobs", claimedJobs.size());
        return claimedJobs;
    }
}
