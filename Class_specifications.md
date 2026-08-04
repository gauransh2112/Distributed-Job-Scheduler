# CLASS_SPECIFICATIONS.md

Version: 1.0

Companion Documents

- 01_PROJECT_SPECIFICATION.md
- 02_ENGINEERING_IMPLEMENTATION_GUIDE.md

---

# Purpose

This document defines every major class that must exist in the project.

Each class specifies

- Purpose
- Responsibilities
- Dependencies
- Public Methods
- Transaction Rules
- Exceptions
- Logging
- Metrics
- Thread Safety
- Notes

Implementation must follow these specifications.

---

# Package Overview

controller/

service/

repository/

scheduler/

worker/

handler/

queue/

locking/

config/

metrics/

mapper/

validation/

entity/

dto/

---

# CONTROLLERS

---

## JobController

Purpose

Expose REST APIs for job operations.

Responsibilities

- Create job
- Get job by id
- List jobs
- Replay dead-lettered job

Dependencies

JobSubmissionService

JobQueryService

ReplayService

Methods

POST /jobs

GET /jobs/{id}

GET /jobs

POST /jobs/{id}/replay

Transaction

None

Exceptions

ValidationException

DuplicateJobException

Logging

Request received

Response sent

Metrics

api_requests_total

---

# SERVICES

---

## JobSubmissionService

Purpose

Accept new jobs.

Responsibilities

Validate request.

Check idempotency.

Persist job.

Return response.

Dependencies

JobRepository

JobMapper

Clock

Methods

createJob()

Transaction

Required

Throws

DuplicateJobException

Logs

Job created.

Metrics

jobs_created_total

---

## JobQueryService

Purpose

Retrieve jobs.

Responsibilities

Find by id.

Find by status.

Pagination.

Dependencies

JobRepository

Methods

getJob()

listJobs()

Transaction

Read only

---

## JobClaimService

Purpose

Claim jobs for execution.

Responsibilities

Fetch due jobs.

Acquire Redis lock.

Mark claimed.

Publish to queue.

Dependencies

JobRepository

RedisLockService

QueuePublisher

Methods

claimJobs()

Transaction

Required

Logs

Jobs claimed.

Metrics

jobs_claimed_total

---

## RetryService

Purpose

Calculate retry timing.

Responsibilities

Increment retry count.

Calculate exponential backoff.

Determine DLQ eligibility.

Methods

calculateDelay()

shouldRetry()

---

## DeadLetterService

Purpose

Move jobs into DLQ.

Responsibilities

Update state.

Publish to DLQ.

Methods

moveToDeadLetter()

---

## ReplayService

Purpose

Replay DLQ jobs.

Responsibilities

Reset state.

Republish.

Methods

replay()

---

## JobStateService

Purpose

Centralize all state transitions.

Responsibilities

Only this service changes job status.

Methods

markClaimed()

markRunning()

markSucceeded()

markFailed()

markDeadLettered()

---

# REPOSITORIES

---

## JobRepository

Purpose

Persist jobs.

Responsibilities

CRUD

Claim query

Pagination

Methods

save()

findById()

findByStatus()

claimBatch()

updateState()

Native Queries

FOR UPDATE SKIP LOCKED

---

# SCHEDULER

---

## JobScheduler

Purpose

Periodic polling.

Responsibilities

Poll database.

Call JobClaimService.

Never execute jobs.

Methods

poll()

Schedule

fixedDelay

Configuration

application.yml

---

# WORKER

---

## JobWorker

Purpose

Consume queue.

Responsibilities

Receive message.

Locate handler.

Execute handler.

Retry.

Release lock.

Dependencies

HandlerRegistry

RetryService

JobStateService

Methods

process()

---

# LOCKING

---

## RedisLockService

Purpose

Distributed locking.

Responsibilities

Acquire.

Renew.

Release.

Verify ownership.

Methods

acquire()

renew()

release()

isOwner()

Configuration

TTL

Heartbeat

---

# QUEUE

---

## QueuePublisher

Purpose

Publish messages.

Responsibilities

Serialize.

Publish.

Handle failures.

Methods

publish()

publishDLQ()

---

## QueueConsumer

Purpose

Receive SQS messages.

Responsibilities

Deserialize.

Dispatch to worker.

Methods

consume()

---

# HANDLERS

---

## JobHandler (Interface)

Purpose

Contract for every job.

Method

execute()

---

## SendEmailJobHandler

Purpose

Demo email job.

Responsibilities

Validate payload.

Simulate email.

---

## GenerateReportJobHandler

Purpose

Generate reports.

Responsibilities

Create report.

Store output.

---

## CleanupJobHandler

Purpose

Cleanup tasks.

Responsibilities

Delete temporary resources.

---

# MAPPERS

---

## JobMapper

Purpose

Entity ↔ DTO conversion.

Methods

toEntity()

toResponse()

---

# CONFIGURATION

---

## RedisConfiguration

Creates Redis beans.

---

## SqsConfiguration

Creates SQS beans.

---

## SchedulerConfiguration

Creates scheduler beans.

---

## MetricsConfiguration

Registers metrics.

---

# METRICS

---

## SchedulerMetrics

Responsibilities

Claim rate

Retry rate

Failure rate

Lock contention

DLQ size

---

# VALIDATION

---

## JobValidator

Purpose

Validate requests.

Methods

validate()

---

# EXCEPTIONS

DuplicateJobException

JobNotFoundException

LockAcquisitionException

QueuePublishException

RetryLimitExceededException

JobExecutionException

InvalidStateTransitionException

---

# ENTITIES

---

## JobEntity

Represents database record.

Contains

id

status

payload

retryCount

claimedBy

scheduledAt

timestamps

No business logic.

---

# DTOS

---

## CreateJobRequest

Purpose

Incoming request.

---

## CreateJobResponse

Purpose

Submission response.

---

## JobResponse

Purpose

Job details.

---

## JobSummaryResponse

Purpose

List API.

---

# FINAL CLASS COUNT

Controllers

1

Services

7

Repositories

1

Schedulers

1

Workers

1

Locking

1

Queue

2

Handlers

4

Configuration

4

Metrics

1

Mapper

1

Validation

1

Entities

1

DTOs

4

Exceptions

7

Total

≈37 production classes

plus

tests

configuration

integration tests

This class inventory should remain stable throughout the project.

New classes should only be introduced when absolutely necessary and documented here before implementation.