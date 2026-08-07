package com.scheduler.job.entity;

/**
 * Represents the lifecycle status of a job in the distributed scheduler.
 */
public enum JobStatus {
    PENDING,
    CLAIMED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    DEAD_LETTERED
}
