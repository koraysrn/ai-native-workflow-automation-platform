# Project Constitution

This document contains the binding rules for all human developers and AI agents within the project.

Rules cannot be violated.

**Document Version:** 2.1.0

**Change Procedure:** This document can only be changed via a PR accompanied by an ADR (Architecture Decision Record). Every change is versioned with semver (MAJOR.MINOR.PATCH).

---

# Version Compatibility Rules

The language and platform versions to be used throughout the project are fixed and managed from a single source.

| Technology | Fixed Version |
|---|---|
| Java | Java 25 (LTS) |
| Spring Boot | 4.1.1 (fixed version) |
| Python | Python 3.14 (conda) |
| TypeScript | TypeScript 6.x (ESLint-compatible) |
| Node.js | The current LTS version supported by TypeScript 6.x and Next.js |

Mandatory rules to prevent version incompatibilities:

1. All libraries are selected from their latest stable versions; the selected version is pinned with a lock file after passing compatibility verification (Maven `pom.xml`/BOM, `package-lock.json`/`pnpm-lock.yaml`, `uv.lock`/`poetry.lock`).
2. Version upgrades are performed only through Renovate/Dependabot bots, after passing automated CI verification.
3. On every version upgrade, OWASP Dependency Scan and `mvn verify` / `npm run build` / `uv sync` must pass without errors.
4. Any PR containing an incompatible version is automatically rejected (CI fail).
5. `--release 25` is used for the `Java 25` build; bytecode compiled with higher/earlier versions is not accepted.
6. For `Python 3.14`, the LangChain/LangGraph/FastAPI dependencies cannot be merged without verifying 3.14 compatibility.
7. For `TypeScript 6.x`, the compatibility of Next.js, React, and all `@types/*` packages is verified with `pnpm typecheck`.
8. Every version change is recorded in the `COMPATIBILITY.md` compatibility matrix.
9. An SBOM (Software Bill of Materials) is generated for every release.
10. Multiple versions of the same library cannot exist in the dependency tree (dependency convergence).

---

# Architecture Principles

Clean Architecture will always be applied.

Domain Driven Design will always be applied.

SOLID principles will always be applied.

Hexagonal Architecture will always be preferred.

The API First approach will always be used.

Event Driven Communication will always be preferred.

**Conflict resolution:** When one rule conflicts with another, the priority order is:
1. Security Rules
2. Data integrity (Database Rules)
3. Domain purity (Domain Rules)
4. Readability / maintainability (Final Principle)
5. Performance optimizations (Performance Rules)

**Package structure (reference):**

```
com.awap.<module>
├── application         (use case, ports)
├── domain              (entity, value object, domain service — no Spring)
├── infrastructure      (adapters, repository impl, external services)
└── interfaces          (controller, dto, event listener)
```

---

# Java Rules

The Java version will always be Java 25.

Java Records will be used (prioritized for DTOs, value objects, and immutable domain objects).

Since JPA entities must be mutable, they are written with non-final fields as classes rather than Records; this is an exception to the Records rule and does not justify the use of Lombok.

The use of Lombok is prohibited.

Field Injection is prohibited.

Only Constructor Injection can be used.

`null` will not be used for optional fields.

Returning null is prohibited.

Compilation will be performed with `--release 25`.

---

# Spring Rules

Spring Boot 4.1.1 will be used.

Business Logic cannot be written in the Controller.

The Controller can only perform orchestration.

Validation must be performed in the Controller layer.

Transaction management must be performed in the Service layer.

The Repository layer is responsible only for data access.

DDD's rich domain model and Spring Modulith are used together: Modulith defines the module boundaries, while Hexagonal defines the intra-module layers. Intra-module dependency always points inward (toward the domain).

---

# Domain Rules

The Domain layer will be framework-independent.

Spring annotations cannot be used inside the Domain.

Domain entities will own business rules.

An anemic domain model cannot be created.

---

# API Rules

REST API will be used.

OpenAPI is mandatory.

Swagger is mandatory.

Breaking changes are prohibited.

API Versioning is mandatory (URI-based: `/api/v1/...`).

The API schema will be the source of the code (OpenAPI first, codegen).

Contract testing is mandatory.

The error format will be RFC 7807 Problem Detail.

API deprecation policy: The old version is supported for at least 6 months; an announcement is made at least 2 versions in advance before removal.

---

# Security Rules

Trust Nothing

Validate Everything

Least Privilege

Zero Trust

Every endpoint must pass authorization control.

Tenant control is mandatory.

IDOR vulnerabilities must be prevented.

Resource-based authorization (object-level authorization) is mandatory.

A `tenant_id` filter is mandatory on every query (cross-tenant access will be prevented).

Secret management is performed with Vault/Sealed Secrets; a secret can never be embedded in plain text in code or config.

Secret rotation policy is mandatory.

HMAC signature and outbound allowlist are mandatory on outgoing calls (webhook); SSRF protection is mandatory.

Uploaded files cannot be stored without passing an antivirus/malware scan.

Script execution (SCRIPT node) is performed inside a sandbox, with limited privileges and a mandatory timeout.

---

# AI Rules

The AI Layer will be developed with Python 3.14 (conda).

Prompts will be versioned.

Prompts will be kept under source control.

An agent cannot perform an operation without using a tool.

Every tool call must be audited.

AI decisions must be explainable.

AI cannot exceed the user's authority (the token/security context is carried to the AI layer and re-verified on every tool call).

Prompt input sanitization is mandatory (Prompt Injection protection).

LLM inputs and outputs are passed through the Guardrails layer (NeMo Guardrails or equivalent); system prompt leakage, jailbreak, and harmful output are prevented.

AI decisions with low confidence scores are downgraded to human approval.

Budget/cap, caching, rate limiting, and model fallback are applied to LLM calls.

Dynamic Model Routing is mandatory: simple queries are routed policy-based to cheap/small models, complex queries to large models; cost, token, and latency metrics are monitored.

The AI model is called through a provider-agnostic layer (adapter) (vendor lock-in is prevented).

---

# Database Rules

Flyway is mandatory.

The schema cannot be changed manually.

Primary keys will be UUID.

Soft delete will be used.

N+1 queries are prohibited.

Lazy/Eager decisions must be made consciously (EntityGraph/DTO projection standard).

Multi-tenancy: Each tenant's data is isolated with `tenant_id` (row-level). The tenant context is propagated in every layer.

Tenant-level rate limiting and resource quota are mandatory; no tenant can consume other tenants' resources.

Optimistic locking (version field) is mandatory on state-changing entities.

Unique constraint and idempotency key are mandatory to guarantee a single approval/request.

---

# Logging Rules

Structured JSON Logging will be used.

Println is prohibited.

Correlation ID is mandatory.

Trace ID is mandatory.

Log levels must be used correctly.

Personal data (PII) cannot be written to logs; masking is mandatory before writing to logs.

The audit log must be immutable.

---

# Testing Rules

A new feature cannot be merged without tests.

Unit testing is mandatory.

Integration testing is mandatory.

Mutation testing will be targeted.

Contract testing is mandatory (Pact).

E2E testing is mandatory.

Coverage: general minimum 80%, critical modules minimum 95%. The coverage gate is mandatory in CI.

Tests must be written for race condition scenarios.

---

# Error Handling Rules

All errors are returned through a single central exception handler.

The error format will be RFC 7807 Problem Detail (`type`, `title`, `status`, `detail`, `instance`).

Error codes are catalogued (`AWAP-<SERVICE>-<CODE>` format).

Domain exceptions must be independent of infrastructure.

Stack traces can never be leaked to the client.

---

# Event Driven & Saga Rules

State-changing inter-service flows must be asynchronous (Kafka).

The Outbox Pattern is mandatory for consistency between the transactional operation and event publication (the event is written in the same atomic operation as the DB transaction; a separate dispatcher sends it to Kafka).

The Saga Pattern is used in flows involving multiple services or external systems (ERP, CRM); a compensating transaction is defined for each step.

Event consumers must be idempotent.

The event schema is managed with Avro + Schema Registry; backward-compatible changes are mandatory.

The outbox table is partitioned, sent records are cleaned up regularly; the dispatcher reads in batches and uses a separate connection pool (DB I/O load is limited).

---

# Timeout & Retry Rules

Timeouts are mandatory on synchronous inter-service calls (default: connection 2s, read 5s).

Retry is performed only on idempotent operations.

Exponential backoff + jitter is used for retry.

At least 3 attempts for asynchronous consumers; afterwards it falls to the Dead Letter Queue.

A DLQ consumer and alarm are mandatory.

---

# Dependency Management Rules

License compatibility and maintenance status are checked before adding a dependency.

OWASP Dependency Check is mandatory in CI.

Multiple versions of the same library are unacceptable.

Dependency versions are pinned with lock files.

An unused dependency cannot be merged.

---

# Git Rules

The main branch will be protected.

Squash merge will be used.

Conventional Commit will be used.

Direct push is prohibited.

All CI gates (build, test, static analysis, dependency scan) must pass before a PR.

---

# Frontend Rules

TypeScript 6.x (ESLint-compatible) will be used.

TypeScript strict mode is mandatory.

The use of `any` is prohibited.

State management will be performed with TanStack Query.

Component-based architecture will be applied.

Business logic cannot be kept inside a component.

XSS and CSRF protection are mandatory; the CSP header is mandatory.

Accessibility (WCAG 2.1 AA) is mandatory.

Core Web Vitals (LCP < 2.5s, CLS < 0.1, INP < 200ms) are targeted.

Jest + Playwright are used in frontend tests.

`package-lock.json` / `pnpm-lock.yaml` is committed; versions are pinned.

---

# Observability Rules

Micrometer is mandatory.

Prometheus is mandatory.

Grafana Dashboard is mandatory.

Distributed tracing is mandatory.

OpenTelemetry will be used.

An SLO is defined for every service and an alerting rule is attached.

---

# Performance Rules

Cache must be considered first.

Redis will be preferred.

Blocking operations must be minimized.

Batch operations must be supported.

A DB index strategy is planned for every query.

Load test (k6/Gatling) is mandatory before release.

The communication between the Backend (Java) and the AI Layer (Python) is designed so as not to violate the P95 < 300ms target; the LLM response time is kept separate from this target (AI Response < 10s).

---

# Data Privacy & Retention Rules

KVKK and GDPR compliance are mandatory.

A data processing inventory is maintained.

Personal data retention periods are defined and automatic deletion/anonymization is applied.

Soft-deleted data is automatically archived or physically deleted at the end of the retention period (tenant-based purging; irreversible deletion as required by the right to be forgotten).

Deletion requests (right to be forgotten) are supported.

The data backup and recovery (restore) procedure is verified with regular drills.

---

# Deployment & Environment Rules

Environments: `dev`, `staging`, `prod`.

Deployment strategy: blue-green or canary for `prod`.

Feature flag management is mandatory.

Secrets are read from the Secret Manager instead of environment variables.

Prod deployment is performed only through a tagged release.

---

# Code Style Rules

Spotless (Google Java Format) is used for Java.

Ruff (format + lint) is used for Python.

ESLint + Prettier are used for TypeScript.

Formatting is verified in CI; unformatted code cannot be merged.

---

# Governance & Verification Rules

Every "mandatory/prohibited" rule in this constitution must be verified with at least one of the following automated checks:

| Rule Group | Verification Tool |
|---|---|
| Architecture / package boundaries | ArchUnit |
| Java code quality | Spotless + Checkstyle + SonarQube |
| Security / dependency | OWASP Dependency Check + Trivy |
| Version compatibility | CI compatibility matrix |
| Test coverage | JaCoCo + PIT (mutation) |
| OpenAPI contract | Spectral lint + contract test |

The rule compliance percentage is reported on every release.

When a violation is detected, the PR is blocked; the merge does not occur until the fix is made within the same PR.

---

# Final Principle

A few milliseconds of performance loss is acceptable for the sake of code readability.

Performance is not preferred at the expense of code complexity.

Systems that are easy to maintain are always prioritized.

The long-term sustainability of the system is more valuable than short-term development speed.

Security and data integrity come before any performance/readability preference.
