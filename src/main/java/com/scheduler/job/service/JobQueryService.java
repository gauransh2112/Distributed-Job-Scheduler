package com.scheduler.job.service;

import com.scheduler.job.dto.response.JobResponse;
import com.scheduler.job.dto.response.JobSummaryResponse;
import com.scheduler.job.entity.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service contract for retrieving job details and paginated listings.
 */
public interface JobQueryService {

    /**
     * Retrieve detailed job information by unique ID.
     *
     * @param id job UUID
     * @return JobResponse DTO containing full job details
     */
    JobResponse getJob(UUID id);

    /**
     * List jobs with optional status filtering and pagination.
     *
     * @param status optional status filter (null for all jobs)
     * @param pageable pagination parameters
     * @return Page of JobSummaryResponse DTOs
     */
    Page<JobSummaryResponse> listJobs(JobStatus status, Pageable pageable);
}
