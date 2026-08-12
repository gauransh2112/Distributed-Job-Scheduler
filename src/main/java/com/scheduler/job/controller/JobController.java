package com.scheduler.job.controller;

import com.scheduler.job.config.JobProperties;
import com.scheduler.job.dto.request.CreateJobRequest;
import com.scheduler.job.dto.response.CreateJobResponse;
import com.scheduler.job.dto.response.JobResponse;
import com.scheduler.job.dto.response.JobSummaryResponse;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.JobValidationException;
import com.scheduler.job.service.JobQueryService;
import com.scheduler.job.service.JobSubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

/**
 * REST controller exposing public HTTP endpoints for job submission and status queries.
 */
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("createdAt", "scheduledAt", "jobType", "status");

    private final JobSubmissionService jobSubmissionService;
    private final JobQueryService jobQueryService;
    private final JobProperties jobProperties;

    /**
     * Submit a new job for scheduling.
     *
     * @param request validated creation request payload DTO
     * @return 201 Created with Location header and response DTO
     */
    @PostMapping
    public ResponseEntity<CreateJobResponse> createJob(@Valid @RequestBody CreateJobRequest request) {
        log.info("REST request to submit job of type: {} with idempotencyKey: {}", request.jobType(), request.idempotencyKey());
        CreateJobResponse response = jobSubmissionService.createJob(request);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    /**
     * Look up detailed job information by ID.
     *
     * @param id job UUID
     * @return 200 OK with JobResponse details
     */
    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJob(@PathVariable UUID id) {
        log.debug("REST request to get job details for ID: {}", id);
        JobResponse response = jobQueryService.getJob(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieve a paginated list of jobs, optionally filtered by status.
     *
     * @param status optional job status filter
     * @param page page number (0-indexed, default 0, min 0)
     * @param size page size (default 20, max bounded by configured jobProperties.maxPageSize)
     * @param sortBy property to sort by (allowed: createdAt, scheduledAt, jobType, status)
     * @param sortOrder sort direction "ASC" or "DESC" (default "DESC")
     * @return 200 OK with Page of JobSummaryResponse DTOs
     */
    @GetMapping
    public ResponseEntity<Page<JobSummaryResponse>> listJobs(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortOrder) {
        log.debug("REST request to list jobs with status: {}, page: {}, size: {}, sortBy: {}, sortOrder: {}",
                status, page, size, sortBy, sortOrder);

        if (page < 0) {
            throw new JobValidationException("Page index cannot be negative");
        }

        if (size < 1 || size > jobProperties.maxPageSize()) {
            throw new JobValidationException(String.format("Page size must be between 1 and %d", jobProperties.maxPageSize()));
        }

        if (!ALLOWED_SORT_FIELDS.contains(sortBy)) {
            throw new JobValidationException(String.format("Invalid sortBy field '%s'. Allowed fields: %s", sortBy, ALLOWED_SORT_FIELDS));
        }

        if (!"ASC".equalsIgnoreCase(sortOrder) && !"DESC".equalsIgnoreCase(sortOrder)) {
            throw new JobValidationException(String.format("Invalid sortOrder '%s'. Allowed values: ASC, DESC", sortOrder));
        }

        Sort.Direction direction = Sort.Direction.fromString(sortOrder.toUpperCase());
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
        Page<JobSummaryResponse> responsePage = jobQueryService.listJobs(status, pageable);
        return ResponseEntity.ok(responsePage);
    }
}
