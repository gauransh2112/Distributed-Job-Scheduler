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

acknowledge()

---

## JobMessage

Added in Phase 2 / Task 4.

Purpose

The wire contract for a job on the queue.

Contains exactly

jobId

jobType

payload

retryCount

traceId

Behavior

The JPA entity is never serialized onto the queue, so persistence-only columns
cannot leak to consumers and schema changes cannot silently change the wire
format.

Validates the contract in its canonical constructor, so the same validation runs
on publish and on deserialization of an inbound message.

A reference plus execution context, not a source of truth: the worker re-reads
the job from PostgreSQL, and the CLAIMED -> RUNNING transition is what
authorizes execution.

---

## ReceivedJobMessage

Added in Phase 2 / Task 4.

Purpose

A deserialized JobMessage plus the SQS delivery metadata needed to acknowledge
it.

Contains

messageId

receiptHandle

message

Behavior

The receipt handle is delivery-scoped rather than message-scoped, so it travels
with the message rather than being derived later.

---

## SqsQueuePublisher

Added in Phase 2 / Task 4.

Purpose

The AWS SQS implementation of QueuePublisher.

Behavior

Serializes a JobMessage and sends it to the main queue or the DLQ.

Every failure path throws QueuePublishException with the cause preserved, which
marks the JobClaimService transaction for rollback.

---

## SqsQueueConsumer

Added in Phase 2 / Task 4.

Purpose

The AWS SQS implementation of QueueConsumer.

Behavior

Receiving and acknowledging are separate operations: a message stays on the
queue until execution reaches a durable outcome, so a worker crash cannot
silently lose a job.

Applies the configured visibility timeout per receive call.

---

## NoopQueuePublisher

Added in Phase 2 / Task 3 as a temporary bridge; retained as an explicit opt-out.

Purpose

A QueuePublisher that discards messages instead of publishing them.

Behavior

Active only when aws.sqs.enabled=false. Mutually exclusive with
SqsQueuePublisher, which is selected whenever that property is absent, so the
no-op publisher cannot be chosen by accident.

Warns at startup that claimed jobs will never execute. Not for deployed use.

---

## SqsQueueUrlProvider

Added in Phase 2 / Task 4.

Purpose

Resolves and caches SQS queue URLs by configured queue name.

Behavior

Resolution is lazy, so application startup does not require the queue to be
reachable, and cached, because a queue URL is stable for the life of the queue.

Auto-creation is configuration gated and intended for local development and
tests; elsewhere a missing queue must surface as an error rather than be
silently created by the application.

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

## PayloadReader

Added in Phase 2 / Task 6.

Purpose

Reads and validates fields from a job payload for handlers.

Responsibilities

Parse the payload.

Require or default individual fields.

Turn every payload problem into a JobExecutionException naming the job type and
the offending field.

Behavior

Package-private and created per execution as a local value, so handlers remain
stateless.

Payload values never appear in exception messages: a message travels into
last_error and the logs, and payloads can carry personal data.

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

## AwsProperties

Added in Phase 2 / Task 4.

Purpose

Binds the aws.* configuration consumed by SqsConfiguration and the queue classes.

Contains

region

credentials

sqs.queue-name

sqs.dlq-name

sqs.endpoint

sqs.visibility-timeout-seconds

sqs.wait-time-seconds

sqs.max-messages-per-poll

sqs.auto-create-queues

Behavior

Bean validated, with no Java-side fallback values, so a missing or out-of-range
operational value fails startup instead of being silently defaulted.

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

HandlerNotFoundException

QueueConsumeException

InvalidQueueMessageException

---

## HandlerNotFoundException

Added in Phase 2 / Task 6.

Purpose

Signals that no JobHandler is registered for a job type.

Behavior

Thrown by HandlerRegistry.resolve() when the job type is unknown or null.

Distinct from JobExecutionException: the work never started, because nothing
knows how to perform it. JobExecutionException means a handler ran and failed.

The distinction is what lets the worker treat an unknown job type as
non-retryable. The registered handler set is fixed for the life of the process,
so redelivering the job cannot produce a different outcome, whereas a failed
execution may succeed on retry.

Never resolved to null or to a silent no-op: an unknown type must not let a job
nobody can run be recorded as if it had run.

---

## QueueConsumeException

Added in Phase 2 / Task 4.

Purpose

Signals a failure of the SQS consumption side.

Behavior

Thrown when receiving from or acknowledging against the queue fails.

Distinct from QueuePublishException, which covers the publication side only.

Always preserves the underlying SDK exception as its cause.

---

## InvalidQueueMessageException

Added in Phase 2 / Task 4.

Purpose

Signals that a received message cannot be turned into a valid job message.

Behavior

Thrown when a message body is unparseable, or is valid JSON that violates the
JobMessage contract.

Handled inside the consumer: such a message is logged and withheld from the
caller, and is deliberately NOT acknowledged, so the visibility timeout expires
and SQS redelivers it. Deleting it would destroy the only executable copy of a
job that is CLAIMED in the database.

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

---

# CLASS ADDITIONS LOG

Classes added after the original inventory, recorded here as the rule above
requires. Each is documented in its own section.

Phase 2 / Task 4 — SQS queue infrastructure

JobMessage — the queue wire contract

ReceivedJobMessage — a deserialized message plus its delivery metadata

SqsQueuePublisher — the SQS implementation of QueuePublisher

SqsQueueConsumer — the SQS implementation of QueueConsumer

SqsQueueUrlProvider — lazy, cached queue URL resolution

AwsProperties — binds aws.* configuration

QueueConsumeException — failure of the consumption side

InvalidQueueMessageException — a message that cannot become a valid job message

Phase 2 / Task 6 — pluggable job handlers

HandlerNotFoundException — no handler registered for a job type

PayloadReader — payload parsing and validation shared by handlers