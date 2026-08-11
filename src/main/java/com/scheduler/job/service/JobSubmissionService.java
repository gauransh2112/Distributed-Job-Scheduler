package com.scheduler.job.service;

import com.scheduler.job.dto.request.CreateJobRequest;
import com.scheduler.job.dto.response.CreateJobResponse;

/**
 * Service contract for submitting new jobs.
 */
public interface JobSubmissionService {

    /**
     * Submit and persist a new job after verifying idempotency.
     *
     * @param request creation payload DTO
     * @return response DTO containing created job details
     */
    CreateJobResponse createJob(CreateJobRequest request);
}
