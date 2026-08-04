# SEQUENCE_FLOWS.md

Version: 1.0

Companion Documents

- 01_PROJECT_SPECIFICATION.md
- 02_ENGINEERING_IMPLEMENTATION_GUIDE.md
- 03_CLASS_SPECIFICATIONS.md

---

# Purpose

This document defines the execution flow for every major operation.

It specifies

- Order of execution
- Class interactions
- Transaction boundaries
- Lock acquisition
- Database updates
- Queue interactions
- Failure handling

No implementation should deviate from these flows.

---

# Flow 1 — Submit Job

Client

↓

POST /api/v1/jobs

↓

JobController

↓

Validate DTO

↓

JobSubmissionService.createJob()

↓

Check idempotency

↓

Persist Job

↓

Return JobResponse

↓

HTTP 201 Created

Transaction

Starts inside JobSubmissionService.

Commits immediately after persistence.

Redis

Not involved.

SQS

Not involved.

Failure

Duplicate idempotency key

↓

409 Conflict

---

# Flow 2 — Scheduler Poll

Spring Scheduler

↓

JobScheduler.poll()

↓

JobClaimService.claimJobs()

↓

JobRepository.claimBatch()

↓

SELECT ...

FOR UPDATE SKIP LOCKED

↓

Return Due Jobs

↓

For each Job

↓

RedisLockService.acquire()

↓

Success?

↓

No

↓

Skip Job

↓

Yes

↓

Mark CLAIMED

↓

QueuePublisher.publish()

↓

Commit

Transaction

Single transaction per claimed batch.

Failure

Queue publish fails

↓

Rollback claim

↓

Log failure

---

# Flow 3 — Worker Execution

SQS

↓

QueueConsumer.consume()

↓

Deserialize Message

↓

JobWorker.process()

↓

JobStateService.markRunning()

↓

HandlerRegistry.find()

↓

JobHandler.execute()

↓

Success?

↓

Yes

↓

JobStateService.markSucceeded()

↓

RedisLockService.release()

↓

Ack Message

Failure

↓

RetryService.handleFailure()

---

# Flow 4 — Retry

Handler throws Exception

↓

RetryService

↓

Increment Retry Count

↓

Retry Count < Max?

↓

Yes

↓

Calculate Backoff

↓

QueuePublisher.publishDelayed()

↓

Update Job Status

↓

PENDING

↓

Release Lock

No

↓

DeadLetterService.moveToDeadLetter()

---

# Flow 5 — Dead Letter

Retry Limit Exceeded

↓

DeadLetterService

↓

Update Status

↓

DEAD_LETTERED

↓

Publish DLQ

↓

Release Lock

↓

Log Event

---

# Flow 6 — Replay Job

Client

↓

POST /jobs/{id}/replay

↓

ReplayController

↓

ReplayService

↓

Load Job

↓

Reset Retry Count

↓

Status = PENDING

↓

Publish Queue

↓

Return Success

---

# Flow 7 — Lock Acquisition

Scheduler

↓

RedisLockService.acquire()

↓

SET NX PX

↓

Success?

↓

Yes

↓

Return Lock

↓

Scheduler Continues

No

↓

Return False

↓

Scheduler Skips Job

---

# Flow 8 — Lock Renewal

Heartbeat Thread

↓

RedisLockService.renew()

↓

Current Owner?

↓

Yes

↓

Extend TTL

↓

Continue

No

↓

Stop Renewal

---

# Flow 9 — Lock Release

Worker Complete

↓

RedisLockService.release()

↓

Verify Owner

↓

Delete Lock

↓

Log Release

---

# Flow 10 — Scheduler Crash

Scheduler Dies

↓

Redis TTL Expires

↓

Another Scheduler Polls

↓

Acquires Lock

↓

Claims Job

↓

Publishes

↓

Execution Continues

Expected Result

No lost job.

No duplicate execution.

---

# Flow 11 — Worker Crash

Worker Dies

↓

Visibility Timeout

↓

Message Reappears

↓

Another Worker Receives

↓

Executes

↓

Idempotency Prevents Duplicate Side Effects

---

# Flow 12 — Database Failure

Repository Throws Exception

↓

Rollback Transaction

↓

Log Error

↓

Retry Scheduler Poll

No Partial State.

---

# Flow 13 — Redis Failure

Lock Acquisition Fails

↓

Job Not Claimed

↓

Scheduler Logs Failure

↓

Retry Next Poll

---

# Flow 14 — SQS Failure

Publish Throws Exception

↓

Rollback Claim

↓

Release Lock

↓

Log Failure

↓

Retry Next Poll

---

# Complete Interaction Diagram

Client

↓

Controller

↓

Service

↓

Repository

↓

Redis Lock

↓

Queue

↓

Worker

↓

Handler

↓

State Update

↓

Metrics

↓

Logs

No component may bypass this interaction order.

---

# Golden Rules

Controllers never call repositories.

Repositories never call Redis.

Repositories never call SQS.

Schedulers never execute jobs.

Workers never poll database.

Handlers never know about Redis.

Handlers never know about SQS.

State transitions only through JobStateService.

Locking only through RedisLockService.

Queue interactions only through QueuePublisher.

These flows define the canonical execution order for the entire project.