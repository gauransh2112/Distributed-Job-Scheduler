package com.scheduler.job.handler;

/**
 * Contract every job type implements.
 *
 * <p>A handler performs business work and nothing else. It knows nothing about PostgreSQL, Redis, SQS,
 * the scheduler, or the retry engine: it receives a payload, does the work, and either returns normally
 * or throws. State transitions, acknowledgement, retries and dead lettering are decided by the worker
 * from that outcome, so a handler cannot accidentally take those decisions itself.
 *
 * <p>The payload arrives as the raw JSON document stored with the job rather than a parsed type. Job
 * identity is deliberately absent from this signature: handlers do not log or branch on job ids, and
 * passing the queue message would couple every handler to the transport layer.
 *
 * <p><strong>Handlers must be stateless.</strong> The same instance is shared by every worker thread
 * and reused across jobs, so an implementation must hold no mutable instance state. Any per-job value
 * belongs in a local variable.
 *
 * <p><strong>Handlers should be written to tolerate being run more than once for the same job.</strong>
 * Queue delivery is at-least-once and a worker can die after the work succeeds but before the outcome
 * is recorded, so a job that ran may be delivered again. The state machine narrows that window but
 * cannot close it.
 */
public interface JobHandler {

    /**
     * The job type this handler serves, matching {@code jobs.job_type}.
     *
     * <p>Must be stable and unique across handlers; two handlers claiming the same type is rejected at
     * startup rather than silently resolved to one of them.
     *
     * @return the job type identifier, never null or blank
     */
    String jobType();

    /**
     * Executes the job.
     *
     * <p>Called outside any database transaction: handlers may be slow, and holding a transaction open
     * across the work would pin a connection and a row lock for its whole duration.
     *
     * @param payload the job payload as a JSON document
     * @throws com.scheduler.job.exception.JobExecutionException when the work fails or the payload is
     *                                                          not valid for this job type
     */
    void execute(String payload);
}
