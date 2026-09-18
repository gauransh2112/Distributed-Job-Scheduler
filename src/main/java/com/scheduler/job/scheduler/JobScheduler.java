package com.scheduler.job.scheduler;

import com.scheduler.job.config.SchedulerProperties;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.service.JobClaimService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Periodically triggers the job claiming workflow.
 *
 * <p>This class is deliberately thin. It owns <em>when</em> claiming happens and nothing else:
 * it does not execute handlers, apply retry logic, touch the repository, talk to SQS, or acquire any
 * distributed lock. All of that belongs to {@link JobClaimService} and the components behind it, so
 * the polling cadence can change without touching execution semantics.
 *
 * <p><strong>Why fixed delay rather than fixed rate.</strong> With a fixed rate, a poll that takes
 * longer than the interval causes the next poll to be due immediately, so slow polls pile up. With a
 * fixed delay the next poll starts only after the previous one returns, which turns a slow database or
 * queue into natural backpressure instead of a growing backlog of overlapping polls. Combined with
 * Spring's single-threaded scheduler, this means polls on one instance never overlap.
 *
 * <p><strong>Concurrent instances.</strong> Several instances may poll at the same time. That is safe
 * without any coordination here, because the claim query locks its rows with
 * {@code FOR UPDATE SKIP LOCKED}: each instance locks a disjoint set of rows and skips the rows another
 * instance already holds, so the same job is never claimed twice. No Redis lock is involved in Phase 2.
 *
 * <p><strong>Crash during a poll.</strong> The claim workflow runs in a single transaction, so a crash
 * mid-poll either commits the whole batch or none of it; partially claimed rows revert to
 * {@code PENDING} and are rediscovered on a later poll. A job that committed as {@code CLAIMED} but
 * whose message never reached the queue stays {@code CLAIMED}; recovering those is Phase 3 work and is
 * intentionally absent here.
 *
 * <p><strong>Failures do not stop the schedule.</strong> A failing poll is logged and swallowed at this
 * boundary by design: the failure has already had its intended effect inside the claim transaction
 * (rollback, so the jobs stay {@code PENDING}), and the documented recovery for a queue or database
 * failure is simply to retry on the next poll. Letting the exception escape would add nothing and risks
 * killing the schedule.
 */
@Component
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobClaimService jobClaimService;
    private final SchedulerProperties schedulerProperties;

    public JobScheduler(JobClaimService jobClaimService, SchedulerProperties schedulerProperties) {
        this.jobClaimService = Objects.requireNonNull(jobClaimService, "jobClaimService must not be null");
        this.schedulerProperties = Objects.requireNonNull(schedulerProperties,
                "schedulerProperties must not be null");
    }

    /**
     * Polls for due jobs and hands them to the claiming workflow.
     *
     * <p>The interval comes from {@code scheduler.poll-interval-ms}; there is no interval literal here.
     */
    @Scheduled(fixedDelayString = "${scheduler.poll-interval-ms}")
    public void poll() {
        int batchSize = schedulerProperties.getBatchSize();
        String instanceId = schedulerProperties.getInstanceId();

        try {
            List<JobEntity> claimedJobs = jobClaimService.claimJobs(batchSize, instanceId);

            if (claimedJobs.isEmpty()) {
                log.debug("Scheduler poll claimed no jobs: instance_id={}, batch_size={}",
                        instanceId, batchSize);
            } else {
                log.info("Scheduler poll claimed {} job(s): instance_id={}, batch_size={}",
                        claimedJobs.size(), instanceId, batchSize);
            }
        } catch (RuntimeException e) {
            // Intentional: the claim transaction has already rolled back, so the jobs remain PENDING
            // and are retried on the next poll. The schedule must survive a failing poll.
            log.error("Scheduler poll failed; jobs remain PENDING and will be retried on the next poll: "
                    + "instance_id={}, batch_size={}", instanceId, batchSize, e);
        }
    }
}
