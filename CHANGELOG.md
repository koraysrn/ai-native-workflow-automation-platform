# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Monorepo scaffold (backend, frontend, ai-layer, infra)
- Pilot version compatibility verification (see `COMPATIBILITY.md`)
- Repository governance (CODEOWNERS, PR template, commitlint, Renovate, Dependabot)
- CI/CD foundations (see `.github/workflows`)
- Advanced workflow nodes: DECISION, CONDITION, WEBHOOK, SUB_PROCESS and explicit
  PARALLEL_GATEWAY/MERGE_GATEWAY aliases, with node-level timeout and retry.
- Deterministic replay/recovery with external-call suppression and a recovery endpoint.
- Notification delivery ledger (idempotent consumer), localized templates, web channel,
  exponential backoff and per-topic dead-letter queues.
- Document API with presigned MinIO uploads/downloads, version history and tenant-scoped access.
- Analytics aggregates in PostgreSQL, an optional OpenSearch raw-event sink and a dashboard API.
- Dedicated outbox relay connection pool, configurable batch size and SECURITY DEFINER relay
  functions so the relay works under row-level security.
- Kafka consumer tenant context so notification/analytics/document writes satisfy RLS.
- Frontend designer support for the new node types plus Analytics and Documents screens.
- OpenAPI contract entries for the new document, notification, analytics and recovery endpoints.
- Live document scan status over tenant-scoped SSE, with a reconnect/polling hook, in-place row
  updates, PROMOTED-only download gating and INFECTED/accessibility announcements.
- Saga compensation for SUB_PROCESS and external integrations: durable `saga_instances`/
  `saga_steps`, reverse-order compensation, per-step retries/timeouts, DLQ, crash recovery,
  manual intervention API, audit rows and metrics.
- Live-stack Playwright journey covering the seeded tenant, dashboard, workflow start, task
  decision, document scan projection and analytics aggregates, with CORS headers for
  `X-Tenant-Id`/`X-XSRF-TOKEN`, an SPA-compatible CSRF request handler, an opt-out for runtime
  bucket provisioning, and distinct Kafka consumer groups for the analytics/usage listeners that
  share a topic.
