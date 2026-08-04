# AI_IMPLEMENTATION_RULEBOOK.md

Version: 1.0

Audience

This document is written specifically for AI-assisted implementation (Claude/Antigravity).

It defines mandatory implementation rules.

The implementation agent must follow this document together with

- 01_PROJECT_SPECIFICATION.md
- 02_ENGINEERING_IMPLEMENTATION_GUIDE.md
- 03_CLASS_SPECIFICATIONS.md
- 04_SEQUENCE_FLOWS.md

This document exists to eliminate architectural drift.

---

# Primary Objective

The goal is not to generate code.

The goal is to generate production-quality code that is

- interview-defensible
- maintainable
- extensible
- consistent

Every implementation decision should optimize for long-term maintainability instead of shortest code.

---

# Golden Rule

The AI should implement.

It should never design.

Every architectural decision has already been made.

Never redesign the project.

Never replace an existing component.

Never introduce new architecture.

---

# Sprint Rule

Implement only the sprint currently requested.

Do not implement future features.

Example

If Sprint 2 asks for scheduling,

do not implement retries.

do not implement DLQ.

do not implement metrics.

do not implement replay.

Stay within sprint boundaries.

---

# Architecture Lock

Never change

Java

Spring Boot

PostgreSQL

Redis

SQS

Docker

Flyway

Actuator

Do not replace any technology.

---

# Layer Rules

Controller

↓

Service

↓

Repository

↓

Database

External services

↓

Redis

↓

SQS

Never bypass layers.

Forbidden

Controller

↓

Repository

Repository

↓

Redis

Repository

↓

SQS

Worker

↓

Controller

---

# Package Rules

Never invent packages.

Never move classes.

Never rename packages.

Use exactly the package structure defined in

03_CLASS_SPECIFICATIONS.md

---

# Class Rules

Never merge two services.

Never split services.

Never rename services.

Every service has one responsibility.

Every class must match the specification.

---

# Method Rules

Every public method should have one purpose.

Avoid methods larger than forty lines.

Extract private helper methods.

Avoid deep nesting.

Prefer early return.

Avoid boolean flags controlling multiple behaviors.

---

# DTO Rules

Never expose entities.

Never return JPA objects.

Always map

Entity

↓

DTO

DTO

↓

Entity

DTOs should be immutable.

Prefer Java records.

---

# Entity Rules

Entities only represent persistence.

No business logic.

No service injection.

No utility methods beyond entity helpers.

---

# Transaction Rules

Database transactions should be short.

Never execute handlers inside transactions.

Claim

↓

Commit

↓

Publish

↓

Execute

Never hold locks during long execution.

---

# Redis Rules

Only RedisLockService may communicate with Redis.

Never access Redis directly from workers.

Never access Redis directly from repositories.

Always verify ownership before releasing a lock.

Heartbeat must renew before TTL expires.

---

# Queue Rules

Only QueuePublisher publishes.

Only QueueConsumer receives.

Messages should contain

jobId

jobType

payload

retryCount

traceId

Nothing more.

---

# Logging Rules

Every important operation logs

jobId

instanceId

traceId

Lock operations additionally log

ttl

owner

key

Failures log

stacktrace

lastError

executionTime

---

# Configuration Rules

Everything configurable.

Never hardcode

batch size

TTL

retry count

poll interval

backoff

timeouts

---

# Exception Rules

Never swallow exceptions.

Every catch block should

log

update state

rethrow if necessary

Never catch Exception unless unavoidable.

---

# Repository Rules

Repositories only

save

update

delete

query

No business logic.

No Redis.

No queue.

No retry.

---

# Scheduler Rules

Scheduler

Poll

↓

Claim

↓

Publish

Nothing else.

Scheduler never executes work.

---

# Worker Rules

Worker

Receive

↓

Deserialize

↓

Handler

↓

Update State

↓

Retry

↓

Release Lock

Worker never polls PostgreSQL.

---

# Handler Rules

Handlers should know nothing about

Redis

SQS

Scheduler

Database

Handlers execute business logic only.

---

# Dependency Injection

Constructor injection only.

No field injection.

No static service references.

---

# Validation Rules

Validate at API boundary.

Never trust payloads.

Never assume optional fields exist.

---

# Spring Rules

Use

@RequiredArgsConstructor

Use

@ConfigurationProperties

Avoid field injection.

Avoid @Value where configuration properties are appropriate.

---

# Database Rules

Use Flyway.

Never use ddl-auto=create.

Never use ddl-auto=update outside local experimentation.

---

# SQL Rules

Use

FOR UPDATE SKIP LOCKED

for claiming.

Never replace with application-side locking.

---

# Testing Rules

Every sprint must include tests.

Unit tests

Business logic

Integration tests

Redis

Postgres

SQS

Never mock Redis locking.

Never mock claim query.

---

# Documentation Rules

Whenever implementation changes architecture,

update documentation first.

Never let implementation diverge from documentation.

---

# Performance Rules

Avoid N+1 queries.

Batch where appropriate.

Avoid repeated Redis lookups.

Avoid unnecessary object creation.

---

# Code Quality Rules

No dead code.

No commented code.

No TODOs in main branch.

No duplicated logic.

No magic strings.

No magic numbers.

---

# Git Rules

Every sprint

↓

Several commits

Every commit

↓

One responsibility

Examples

feat: implement job submission service

feat: add redis distributed locking

test: scheduler integration tests

docs: update sequence flows

---

# Interview Rules

Every class should be explainable.

Every method should have a reason.

Every dependency should have a reason.

Every transaction should have a reason.

Every lock should have a reason.

If a design decision cannot be defended in an interview,

do not implement it.

---

# Completion Checklist

Before considering any sprint complete

✓ Build passes

✓ Tests pass

✓ Logs added

✓ Metrics added

✓ Exceptions handled

✓ Transactions correct

✓ Configuration externalized

✓ Documentation updated

✓ No architectural violations

✓ Sprint objectives satisfied

Only then proceed to the next sprint.

---

# Final Rule

Whenever multiple valid implementations exist,

choose the implementation that

1. Matches the project specification.

2. Matches the implementation guide.

3. Matches the class specifications.

4. Matches the sequence flows.

5. Produces the simplest maintainable code.

Never optimize for fewer lines of code.

Always optimize for correctness and maintainability.