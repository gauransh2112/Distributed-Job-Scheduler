<div align="center">

# 🚀 Distributed Job Scheduler

### A Production-Inspired Distributed Job Scheduler built with Java, Spring Boot, PostgreSQL, Redis & AWS SQS

*Reliable background job processing with distributed locking, fault tolerance, retry semantics, and horizontal scalability.*

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)
![Redis](https://img.shields.io/badge/Redis-Latest-red)
![AWS SQS](https://img.shields.io/badge/AWS-SQS-yellow)
![Docker](https://img.shields.io/badge/Docker-Compose-blue)
![License](https://img.shields.io/badge/License-MIT-green)

</div>

---

# 📖 Overview

Modern applications rarely execute every task immediately.

Operations like sending emails, generating reports, processing payments, or creating invoices are typically executed asynchronously in the background.

This project implements a **production-inspired Distributed Job Scheduler** capable of safely executing background jobs across multiple scheduler instances while ensuring reliability, scalability, and fault tolerance.

Rather than focusing on CRUD operations, this project explores the engineering concepts behind modern distributed backend systems.

---

# ✨ Features

- 🚀 Production-inspired Spring Boot architecture
- 📦 Asynchronous job execution
- 🗄 PostgreSQL as the source of truth
- 🔒 Redis distributed locking
- 📨 AWS SQS (LocalStack) integration
- ⚡ `FOR UPDATE SKIP LOCKED` job claiming
- 🔁 Exponential Retry Mechanism
- 💀 Dead Letter Queue (DLQ)
- 📊 Metrics & Observability
- 📈 Horizontal Scaling
- 🧪 Testcontainers Integration Testing
- 🐳 Docker Compose Development Environment

---

# 🏗 High-Level Architecture

```text
                 +----------------+
                 |     Client     |
                 +--------+-------+
                          |
                    REST API (Spring Boot)
                          |
                          ▼
                +----------------------+
                |    PostgreSQL DB     |
                +----------+-----------+
                           |
               Scheduler Polling Engine
                           |
             FOR UPDATE SKIP LOCKED
                           |
                           ▼
               Redis Distributed Lock
                           |
                           ▼
                   AWS SQS Queue
                           |
                           ▼
                Multiple Worker Nodes
                           |
                           ▼
                    Job Handlers
```

---

# 🛠 Tech Stack

| Category | Technology |
|----------|------------|
| Language | Java 17 |
| Framework | Spring Boot 3 |
| Database | PostgreSQL |
| Distributed Locking | Redis |
| Queue | AWS SQS (LocalStack) |
| ORM | Spring Data JPA |
| Migrations | Flyway |
| Object Mapping | MapStruct |
| Build Tool | Maven |
| Testing | JUnit 5 + Testcontainers |
| Monitoring | Micrometer + Prometheus + Grafana |
| Containers | Docker Compose |

---

# 🎯 Learning Goals

This project focuses on mastering production backend concepts such as:

- Distributed Systems
- Fault Tolerance
- Background Job Processing
- Distributed Locking
- Concurrency Control
- Horizontal Scaling
- Retry & Recovery Strategies
- Dead Letter Queues
- Production Observability
- Integration Testing

---

# 🗺 Roadmap

## ✅ Phase 0 — Project Foundation

- Spring Boot Setup
- Docker Compose
- PostgreSQL
- Redis
- LocalStack
- Flyway
- Logging
- Health Checks
- Production-ready Infrastructure

---

## 🚧 Phase 1 — Core Job Management

- Domain Model
- DTOs
- MapStruct
- REST APIs
- Validation
- Repository Layer
- Service Layer
- PostgreSQL Persistence

---

## 🚧 Phase 2 — Job Execution Engine

- Scheduler
- Worker
- `FOR UPDATE SKIP LOCKED`
- Retry Mechanism
- Exponential Backoff
- Dead Letter Queue
- Failure Recovery

---

## 🚧 Phase 3 — Distributed Systems & Production Hardening

- Redis Distributed Locking
- Heartbeats
- Multi-instance Coordination
- Crash Recovery
- Horizontal Scaling
- Chaos Testing
- Performance Testing
- Metrics & Observability
- Production Polish

---

# 📂 Project Structure

```text
src
├── controller
├── service
├── repository
├── scheduler
├── worker
├── handler
├── locking
├── queue
├── dto
├── entity
├── mapper
├── validation
├── metrics
├── exception
└── config
```

---

# 🚀 Current Status

| Phase | Status |
|-------|--------|
| Phase 0 | 🚧 In Progress |
| Phase 1 | ⏳ Pending |
| Phase 2 | ⏳ Pending |
| Phase 3 | ⏳ Pending |

---

# 🎓 Why This Project?

Most portfolio projects demonstrate CRUD operations.

This project demonstrates how real backend systems coordinate multiple nodes, recover from failures, process asynchronous workloads, and maintain consistency in distributed environments.

It is designed to strengthen understanding of production backend engineering and distributed systems while serving as a portfolio project for backend software engineering roles.

---

# 📄 License

This project is licensed under the MIT License.