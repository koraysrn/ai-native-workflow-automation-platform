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
