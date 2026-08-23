package com.scheduler.job.service;

import com.scheduler.job.entity.JobEntity;

import java.util.List;

/**
 * Service coordinating the job claiming workflow.
 * Fetches due PENDING jobs, updates state to CLAIMED, and publishes messages to SQS queue.
 */
public interface JobClaimService {

    /**
     * Claim a batch of due jobs using default configured batch size and instance ID.
     *
     * @return List of claimed JobEntity records
     */
    List<JobEntity> claimJobs();

    /**
     * Claim a batch of due jobs using explicit batch size and instance ID.
     *
     * @param batchSize maximum number of jobs to claim
     * @param instanceId identifier of the claiming scheduler instance
     * @return List of claimed JobEntity records
     */
    List<JobEntity> claimJobs(int batchSize, String instanceId);
}
