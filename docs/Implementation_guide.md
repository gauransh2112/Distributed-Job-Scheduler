# Distributed Job Scheduler — Implementation Guide

Version: 1.0

Companion Document:
01_PROJECT_SPECIFICATION.md

---

# Purpose

This document defines **how the project must be implemented**.

The specification defines **what** to build.

This guide defines

- project structure
- coding conventions
- package responsibilities
- dependency rules
- transaction boundaries
- logging
- exception handling
- testing philosophy

If any implementation conflicts with this guide, this guide takes precedence unless the specification explicitly states otherwise.

---

# Engineering Philosophy

The project should resemble code written by a backend infrastructure team.

Primary goals (highest priority first)

1. Correctness
2. Fault tolerance
3. Readability
4. Maintainability
5. Performance

Never trade correctness for cleverness.

Avoid "smart" code.

Prefer explicit code over concise code.

Every class should have one clearly defined responsibility.

---

# Architecture Principles

Use a strict layered architecture.

Controller

↓

Service

↓

Repository

↓

PostgreSQL

External integrations

Service

↓

Redis

↓

SQS

Rules

Controllers never access repositories.

Repositories never call external services.

Repositories never contain business logic.

Workers never call controllers.

Schedulers never execute jobs.

Job handlers never poll databases.

Redis should only be accessed through the locking package.

SQS should only be accessed through queue publisher/consumer abstractions.

---

# Project Structure

src/main/java

com.antigravity.scheduler

config/

controller/

dto/

request/

response/

entity/

repository/

service/

impl/

scheduler/

worker/

handler/

queue/

locking/

metrics/

exception/

mapper/

validation/

util/

---

# Package Responsibilities

config

Contains Spring configuration classes.

Only bean definitions belong here.

No business logic.

---

controller

Expose REST endpoints.

Responsibilities

Validate request

Call service

Return response

Nothing else.

Controllers must never

Access repository

Call Redis

Call SQS

Perform business logic

---

service

Contains all business logic.

Responsible for

validation

state transitions

claiming jobs

retry decisions

dead letter routing

publishing

replay

metrics

Services coordinate work.

Services never execute SQL.

---

repository

Persistence only.

Contains

JPA repositories

Custom SQL

Native queries

Repositories never

call Redis

call SQS

perform retries

perform validation

---

scheduler

Responsible only for

polling

claiming

locking

publishing to queue

Scheduler never executes jobs.

---

worker

Consumes SQS.

Responsible for

finding handler

executing handler

updating state

retry logic

lock release

Nothing else.

---

handler

Every job type implements

JobHandler

Handlers must be stateless.

Handlers should never know about Redis.

Handlers should never know about scheduling.

---

locking

Owns every Redis interaction.

Only this package communicates with Redis.

Provides

Acquire lock

Renew lock

Release lock

Ownership verification

---

queue

Contains

publisher

consumer

serialization

deserialization

Queue implementation must be replaceable without changing services.

---

metrics

Contains Micrometer metrics.

No business logic.

---

exception

Contains custom exceptions only.

---

validation

Contains custom validators.

---

mapper

Entity ↔ DTO conversion.

No business logic.

---

# Dependency Rules

Allowed

Controller

↓

Service

↓

Repository

Allowed

Service

↓

QueuePublisher

Allowed

Service

↓

RedisLockService

Forbidden

Controller

↓

Repository

Forbidden

Repository

↓

Service

Forbidden

Worker

↓

Controller

Forbidden

Repository

↓

Redis

Forbidden

Repository

↓

SQS

No circular dependencies.

---

# Dependency Injection

Use constructor injection only.

Never use field injection.

Every injected dependency must be final.

No optional dependencies unless absolutely required.

---

# Spring Standards

Use

@ConfigurationProperties

for configuration.

Avoid @Value except for tiny constants.

Every configuration belongs in

application.yml

Never access environment variables directly inside business code.

---

# Transactions

Every transaction must have an obvious boundary.

Never annotate an entire service.

Annotate methods only.

Long-running work must never occur inside a database transaction.

Database transactions should contain only

read

claim

update

commit

Job execution happens outside transactions.

---

# State Management

Only services may change job state.

Repositories never change state automatically.

Every transition must be explicit.

Every transition must be logged.

Invalid transitions must throw exceptions.

---

# Redis Locking Rules

Acquire lock

↓

Verify ownership

↓

Claim job

↓

Publish

↓

Renew periodically

↓

Release

Rules

Never release a lock you do not own.

Never overwrite another lock.

Lock TTL must be configurable.

Heartbeat interval must be configurable.

Log every acquire.

Log every renewal.

Log every release.

---

# Queue Rules

Never publish directly from controller.

Only services publish.

Messages must contain

job id

job type

payload

retry count

trace id

No database entities inside queue messages.

---

# Exception Strategy

Every failure must have a meaningful exception.

Create domain-specific exceptions.

Examples

DuplicateJobException

LockAcquisitionException

QueuePublishException

RetryLimitExceededException

JobExecutionException

JobNotFoundException

Never throw generic Exception.

Never silently swallow exceptions.

---

# Logging Standard

Every log line must include

job_id

instance_id

trace_id

Lock logs

lock_key

owner

ttl

Execution logs

retry_count

execution_time

job_type

Failures

stacktrace

last_error

No sensitive payloads in logs.

---

# Configuration Rules

Everything configurable.

Examples

batch size

poll interval

lock ttl

heartbeat interval

retry limit

retry delay

backoff multiplier

visibility timeout

No magic numbers.

---

# DTO Rules

Never expose entities.

Never expose internal IDs unless required.

DTOs must be immutable.

Prefer Java records.

Validation annotations belong on DTOs.

---

# Entity Rules

Entities represent persistence only.

No business logic.

No service calls.

No external dependencies.

---

# Mapping Rules

Use MapStruct.

No manual mapping unless trivial.

Keep mapping outside services.

---

# Scheduler Rules

Scheduler responsibilities

poll

claim

lock

publish

metrics

Nothing else.

Scheduler must never execute jobs.

---

# Worker Rules

Worker responsibilities

consume

deserialize

find handler

execute

retry

release lock

update state

Nothing else.

---

# Job Handler Rules

Every handler implements JobHandler.

Handlers are stateless.

Handlers never know

Redis

SQS

scheduler

database

Handlers only execute business work.

---

# Naming Conventions

Classes

JobSubmissionService

JobScheduler

JobWorker

RedisLockService

RetryService

ReplayService

QueuePublisher

QueueConsumer

DTOs

CreateJobRequest

CreateJobResponse

JobStatusResponse

Repositories

JobRepository

Configurations

RedisConfiguration

SqsConfiguration

MetricsConfiguration

---

# Testing Philosophy

Business logic

↓

Unit tests

Concurrency

↓

Integration tests

Redis

↓

Testcontainers

PostgreSQL

↓

Testcontainers

SQS

↓

LocalStack

Never mock distributed locking.

Never mock database claiming.

---

# Code Style

Maximum method size

≈40 lines

Maximum class size

≈300 lines

Prefer early returns.

Avoid nested conditionals.

Prefer composition over inheritance.

No commented code.

No TODOs on main branch.

---

# Commit Strategy

Each phase should contain meaningful commits.

Examples

feat: add job submission api

feat: implement scheduler polling

feat: implement redis distributed locking

feat: add sqs worker

feat: implement retry backoff

test: add redis integration tests

docs: update implementation guide

---

# Definition of Complete Code

Code is considered complete only if

✓ Feature implemented

✓ Integration tests pass

✓ Logs added

✓ Metrics added

✓ Exceptions handled

✓ Transactions correct

✓ README updated

✓ No hardcoded values

✓ Build passes

✓ No architectural violations

---

# AI Implementation Rules

This section is written specifically for AI-assisted implementation.

The implementation agent must

Never invent new packages.

Never invent additional architectural layers.

Never introduce libraries outside the project specification.

Never rename existing classes unless instructed.

Never replace PostgreSQL with another datastore.

Never replace Redis locking.

Never replace SQS.

Never change transaction boundaries.

Never bypass service layer.

Never expose entities through REST.

Never hardcode configuration.

Implement exactly the current sprint.

Preserve backwards compatibility with previously implemented phases.

Generate production-quality code rather than tutorial code.

Whenever implementation ambiguity exists,

prefer consistency with this guide over personal assumptions.