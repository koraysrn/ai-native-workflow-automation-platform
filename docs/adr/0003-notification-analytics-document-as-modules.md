# ADR-003: Notification, Analytics and Document as Modular-Monolith Modules

- **Status:** Accepted
- **Date:** 2026-10-23

## Context

The roadmap lists `Notification Service`, `Document Service` and
`Analytics Service`. ADR-001 established a modular monolith: every domain lives
under `com.awap.<module>` inside the single `identity` deployment unit, with
Clean/Hexagonal layers and Spring Modulith module boundaries. Before building
these capabilities we must decide whether they are separate deployable services
or modules of the existing monolith.

## Decision

1. **Notification, Analytics and Document are modules of the modular monolith**
   (`com.awap.notification`, `com.awap.analytics`, `com.awap.file`), not separate
   microservices. Rationale:
   - ADR-001 already fixed a single CI pipeline and single deployable.
   - They share the transactional outbox, tenant context and repositories.
   - Separating them now would duplicate the outbox/tenant/observability spine
     without a scaling driver.
2. Module boundaries follow the existing convention:
   `application / domain / infrastructure / interfaces`, domain stays
   Spring-free, dependencies point inward.
3. Cross-module communication stays event-driven via Kafka with Avro schemas
   (`notification.send`, `analytics.events`, document events); direct intra-module
   calls are only allowed for read-only, low-latency queries.

## Consequences

- Positive: one build, one deploy, one outbox, consistent tenant isolation.
- Negative: a hot notification/document workload scales together with the rest
  of the monolith; if it becomes a bottleneck, extract the module into a service
  later (strangler pattern). This extraction is explicitly deferred and must be
  justified by measured load, not predicted load.
