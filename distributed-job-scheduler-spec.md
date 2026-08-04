# Distributed Job Scheduler — Implementation Specification

**Owner:** Gauransh Nuwal
**Purpose of this document:** This is the single source of truth for building this project. It contains every architectural decision, every phase, every do/don't. Do not ask "what should I build" — it's all here. If something is genuinely ambiguous and not covered below, make the most production-sound choice and document it in `DECISIONS.md`, don't stall.

---

## 1. What We're Building

A distributed job scheduler that accepts jobs (immediate or delayed), guarantees each job runs **exactly once effectively** (at-least-once delivery + idempotency, not literal exactly-once), survives worker crashes, retries failed jobs with backoff, and routes permanently-failing jobs to a dead-letter queue (DLQ).

This is **not** a cron wrapper. The reason this project exists on a resume is to demonstrate three specific distributed-systems competencies:

1. **Leader election / distributed locking** — when multiple scheduler instances run concurrently, only one may dispatch a given job. No duplicate dispatch, no dropped jobs.
2. **Fault tolerance** — a worker crash mid-job must not lose the job or execute it twice on recovery.
3. **Backpressure & retry semantics** — exponential backoff, max-retry ceiling, DLQ, and a path to requeue from the DLQ.

Every phase below is designed to produce evidence (logs, benchmarks, a chaos-test recording) for these three points, because that's what an interviewer will ask about.

---

## 2. Tech Stack (locked — do not substitute without a documented reason)

| Layer | Choice | Why |
|---|---|---|
| Language | Java 17+ | Matches existing skill profile; use records, virtual threads if targeting Java 21 |
| Framework | Spring Boot 3.x | Matches existing Gateway project; consistent portfolio story |
| Persistence (job state) | PostgreSQL | Durable source of truth for job status: `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `DEAD_LETTERED` |
| Distributed locking / leader election | Redis (`SET NX PX` + Redlock pattern, or Spring Integration's `RedisLockRegistry`) | Already used in the Gateway project — reuse the mental model, deepen it |
| Queueing | AWS SQS (Standard queue, not FIFO — retry/backoff logic is handled by the app, not the queue) | Closes the AWS gap on the resume; use **LocalStack** for local dev so this costs nothing during development |
| Scheduling trigger | Spring `@Scheduled` poll loop (fixed delay, e.g. every 5s) checking for due `PENDING` jobs — NOT Quartz. Building the scheduling logic yourself is the point of the project. | |
| Containerization | Docker Compose (Postgres + Redis + LocalStack + N scheduler instances) | Must be able to demo 3 scheduler instances locally with one command |
| Observability | Micrometer + Prometheus + Grafana (or minimum: structured JSON logs + a `/actuator/metrics` endpoint) | |
| Testing | JUnit 5, Testcontainers (real Postgres/Redis in tests, not mocks, for the concurrency-critical paths) | |
| API | REST, versioned (`/api/v1/...`) | |

**Do NOT** introduce Kafka, Kubernetes, or a message broker beyond SQS. Scope creep kills portfolio projects — depth on 3 concepts beats shallow coverage of 10.

---

## 3. Architecture Overview

```
Client → REST API (Job Submission) → Postgres (job persisted as PENDING)
                                            ↑
                    ┌───────────────────────┴────────────────────────┐
                    │   N Scheduler Instances (horizontally scaled)   │
                    │   each polls Postgres for due PENDING jobs      │
                    │   each job dispatch requires a Redis lock       │
                    │   (key = job_id) before claiming the job        │
                    └───────────────────────┬────────────────────────┘
                                            ↓
                                  AWS SQS (task queue)
                                            ↓
                              Worker Pool (consumes SQS)
                                            ↓
                        Executes job → success → mark SUCCEEDED
                                     → failure → increment retry_count
                                          → retry_count < max → requeue with backoff
                                          → retry_count >= max → DLQ (separate SQS queue)
```

Key design decision: **the scheduler claims jobs, the worker executes them.** This separation is what makes the leader-election/locking story clean — locking happens once, at claim time, not at execution time.

---

## 4. Data Model

```sql
CREATE TABLE jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type        VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
        -- PENDING | CLAIMED | RUNNING | SUCCEEDED | FAILED | DEAD_LETTERED
    scheduled_at    TIMESTAMPTZ NOT NULL,      -- when it should run
    claimed_by      VARCHAR(100),              -- instance id that holds the lock
    retry_count     INT NOT NULL DEFAULT 0,
    max_retries     INT NOT NULL DEFAULT 5,
    last_error      TEXT,
    idempotency_key VARCHAR(200) UNIQUE,       -- prevents duplicate submission
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_status_scheduled ON jobs (status, scheduled_at)
    WHERE status = 'PENDING';   -- partial index, this is the hot query
```

`idempotency_key` is mandatory on submission — this is what makes "at-least-once delivery" safe. Document this explicitly in the README as a deliberate design choice, not an afterthought.

---

## 5. Phase-Wise Execution Plan

### Phase 0 — Scaffolding (Day 1)
- Spring Boot project via Spring Initializr: Web, Data JPA, Actuator, Validation
- Docker Compose file: Postgres, Redis, LocalStack (SQS)
- Flyway or Liquibase for schema migrations — **do not** use `hibernate.ddl-auto=update` in anything beyond local scratch work
- `application.yml` per profile: `local`, `docker`, `prod`
- **Deliverable:** `docker-compose up` brings up all infra; app connects and health-checks green.

### Phase 1 — Job Submission API (Day 2–3)
- `POST /api/v1/jobs` — accepts `job_type`, `payload`, `scheduled_at`, `idempotency_key`
- Validate: reject duplicate `idempotency_key` with 409, not a generic 500
- `GET /api/v1/jobs/{id}` — status lookup
- `GET /api/v1/jobs?status=` — filtered listing, paginated
- **Deliverable:** full CRUD + validation, tested with Postman/curl collection saved in `/docs/api-examples.http`

### Phase 2 — Single-Instance Scheduling Loop (Day 4–5)
- `@Scheduled(fixedDelay = 5000)` poll: `SELECT ... WHERE status='PENDING' AND scheduled_at <= now() LIMIT 50 FOR UPDATE SKIP LOCKED`
- On claim: push to SQS, update status to `CLAIMED`
- Build the worker: SQS consumer, executes a **pluggable job handler interface** (`JobHandler.execute(payload)`) — implement 2–3 dummy handlers (e.g., `SendEmailJobHandler`, `GenerateReportJobHandler`) so the project isn't abstract
- **Deliverable:** single instance reliably schedules and executes jobs end to end.

### Phase 3 — Distributed Locking / Leader Coordination (Day 6–8) — **this is the core of the project**
- Scale to 3 scheduler instances via Docker Compose (`docker-compose up --scale scheduler=3`)
- Before claiming a batch of jobs, each instance must acquire a Redis lock per job (`SET job-lock:{id} {instance_id} NX PX 30000`)
- If lock acquisition fails, skip that job (another instance has it)
- Implement lock renewal (heartbeat) for long-running jobs so the lock doesn't expire mid-execution
- Implement lock release on completion/failure
- **Chaos test:** kill an instance mid-job (`docker stop`), verify the lock expires and another instance picks up the job with no duplicate execution. **Record this test** — it's your interview evidence.
- **Deliverable:** 3 instances running concurrently, zero duplicate job execution under kill-testing, documented in `CHAOS_TESTS.md`.

### Phase 4 — Retry, Backoff, and Dead Letter Queue (Day 9–10)
- On job failure: exponential backoff (`base * 2^retry_count`, capped), requeue via SQS with a delay
- On `retry_count >= max_retries`: move to DLQ (separate SQS queue), status → `DEAD_LETTERED`
- `POST /api/v1/jobs/{id}/replay` — admin endpoint to requeue a dead-lettered job
- **Deliverable:** a job configured to always fail correctly lands in the DLQ after N attempts, is visible via API, and can be replayed.

### Phase 5 — Observability (Day 11–12)
- Structured JSON logs with `job_id`, `instance_id`, `trace_id` on every log line
- Metrics: jobs claimed/sec, jobs succeeded/failed, current DLQ depth, lock contention rate
- `/actuator/prometheus` endpoint; a minimal Grafana dashboard JSON checked into `/observability/`
- **Deliverable:** a screenshot/GIF of the dashboard under load, included in the README.

### Phase 6 — Load Testing & Benchmarking (Day 13)
- Use `k6` or a simple JMH/custom load generator to submit 10k jobs
- Measure: throughput (jobs/sec), p50/p95/p99 dispatch latency, lock contention overhead at 1 vs 3 vs 5 instances
- **Deliverable:** a benchmarks table in the README — this is what turns "I built a scheduler" into "I built a scheduler that sustains X jobs/sec with Y ms p99 latency across N nodes," which is the sentence that belongs on the resume.

### Phase 7 — Polish & Documentation (Day 14)
- README: architecture diagram, setup instructions (`docker-compose up` → working demo in under 5 minutes), API docs, benchmark results, chaos test results
- `DECISIONS.md`: every non-obvious choice and why (e.g., "chose SQS Standard over FIFO because retry ordering is handled at the application layer")
- Clean up: remove dead code, ensure `mvn clean install` passes with zero warnings, add a `LICENSE`

---

## 6. Antigravity — Do's

- **Do** write Testcontainers-based integration tests for anything touching Redis locking or the Postgres claim query — this is the code path where bugs are invisible until concurrency exposes them, and a mocked test proves nothing here.
- **Do** use `FOR UPDATE SKIP LOCKED` in the Postgres claim query, not application-level filtering — this is a real production pattern and worth calling out explicitly in code comments.
- **Do** make lock TTLs and retry backoff values configurable via `application.yml`, not hardcoded.
- **Do** log every lock acquisition, renewal, and release at INFO level — this is the audit trail that proves correctness during chaos testing.
- **Do** commit incrementally, one phase = one or more meaningful commits with clear messages (`feat: add redis-based job claiming with lock renewal`), not one giant commit at the end.
- **Do** write the README's "How to run" section first and keep it accurate as you go — assume a reviewer will run it, not read it.
- **Do** ask (in `DECISIONS.md`, not by stalling) when a genuine tradeoff appears with no clear right answer, and pick the more defensible option yourself, then note it.

## 7. Antigravity — Don'ts

- **Don't** use `hibernate.ddl-auto=update` or `create-drop` anywhere except a throwaway local profile — use migrations.
- **Don't** implement leader election with a naive `synchronized` block or in-memory lock — it must be Redis-backed and correct across process boundaries, or the entire point of the project is lost.
- **Don't** mock Redis or Postgres in the concurrency tests. A passing mocked test for a distributed lock is worse than no test — it creates false confidence.
- **Don't** add Kafka, Kubernetes, service mesh, or any technology not listed in Section 2. If it seems needed, it's scope creep — flag it in `DECISIONS.md` instead of adding it silently.
- **Don't** hardcode AWS credentials or Redis passwords anywhere in source. Use environment variables and document them in `.env.example`.
- **Don't** skip the chaos test in Phase 3 to save time — it is the single most important deliverable in this project. Everything else is supporting infrastructure for that one proof point.
- **Don't** leave `TODO` or placeholder job handlers as the only demo content — implement at least 2 realistic ones so a reviewer can see real payloads flowing through.
- **Don't** silently swallow exceptions in the worker's job execution path — every failure must update `retry_count`/`last_error` and be logged with `job_id`.

---

## 8. Definition of Done

- [ ] `docker-compose up` brings up the full stack (Postgres, Redis, LocalStack, 3 scheduler instances) with zero manual steps
- [ ] A job submitted via API executes successfully end-to-end
- [ ] Killing one of 3 running instances mid-job does not duplicate or lose the job (chaos test recorded)
- [ ] A permanently-failing job correctly lands in the DLQ after max retries and can be replayed
- [ ] Load test results (throughput, p99 latency, lock contention at N instances) are documented in the README
- [ ] README allows a stranger to clone, run, and understand the project in under 10 minutes
- [ ] `DECISIONS.md` documents every non-default architectural choice

If every box above is checked, this project is resume-ready and interview-defensible.
