package com.scheduler.job.repository;

import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for JobEntity persistence operations.
 * Includes Phase 2 PostgreSQL atomic claiming queries (FOR UPDATE SKIP LOCKED).
 */
@Repository
public interface JobRepository extends JpaRepository<JobEntity, UUID> {

    /**
     * Find job by idempotency key to enforce single-submission semantics.
     *
     * @param idempotencyKey unique submission key provided by client
     * @return Optional containing matching JobEntity if found
     */
    Optional<JobEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * Retrieve paginated list of jobs filtered by status.
     *
     * @param status job lifecycle status filter
     * @param pageable pagination parameters (page, size, sort)
     * @return Page of matching JobEntity records
     */
    Page<JobEntity> findByStatus(JobStatus status, Pageable pageable);

    /**
     * Atomically select and row-lock due PENDING jobs for claiming using PostgreSQL FOR UPDATE SKIP LOCKED.
     * Concurrently executing schedulers will skip rows currently locked by other transactions, preventing wait-blocking.
     *
     * @param now current timestamp to filter due jobs (scheduled_at <= now)
     * @param limit maximum batch size to claim
     * @return List of locked JobEntity records ready for claiming
     */
    @Query(value = """
            SELECT * FROM jobs
            WHERE status = 'PENDING'
              AND scheduled_at <= :now
            ORDER BY scheduled_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<JobEntity> findEligibleJobsForClaim(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    /**
     * Atomically transitions a job from CLAIMED to RUNNING, returning how many rows changed.
     *
     * <p>This is the execution-ownership gate. It is expressed as a single conditional UPDATE — a
     * compare-and-swap — rather than a read, a check and a write, because those three steps are not
     * atomic together: two transactions can both read CLAIMED, both pass the check, and both write
     * RUNNING, so both workers would execute the handler.
     *
     * <p>As one statement, PostgreSQL takes a row-level write lock. A second transaction blocks until
     * the first commits and then, under PostgreSQL's default READ COMMITTED behaviour, re-evaluates the
     * WHERE clause against the updated row, where the status is no longer CLAIMED. It therefore matches
     * no rows and returns 0. That is what provides the execution gate here: no explicit locking and no
     * {@code @Version} column are needed at READ COMMITTED, which is the isolation level this
     * application runs at. No claim is made about other isolation levels or other databases.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} does not cover this transition: it guards PENDING to CLAIMED
     * during scheduler claiming, not the worker's gate.
     *
     * <p>{@code updated_at} is set here because a native update bypasses the JPA {@code @PreUpdate}
     * callback.
     *
     * @param jobId the job to transition
     * @param now   timestamp to record as updated_at
     * @return 1 if this caller won the gate, 0 if the job was not CLAIMED or does not exist
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE jobs
            SET status = 'RUNNING', updated_at = :now
            WHERE id = :jobId
              AND status = 'CLAIMED'
            """, nativeQuery = true)
    int markRunningIfClaimed(
            @Param("jobId") UUID jobId,
            @Param("now") Instant now
    );
}

