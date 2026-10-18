# ADR 0002 — Tenant Isolation: RLS + JPA Filter

- Status: accepted
- Date: 2026-10-17

## Context

The platform requires strict tenant data isolation. PostgreSQL Row-Level Security (RLS) is already
enforced as the hard guarantee via migrations V7, V13, V14, V15 and V21. The original plan also
called for a mandatory Hibernate/JPA tenant filter on every query as an application-layer
defense-in-depth measure.

## Decision

1. **PostgreSQL RLS remains the primary, hard isolation mechanism.** Every tenant-scoped table keeps
   its RLS policies and the `awap.tenant_id` context is propagated per transaction by
   [`TenantContextTransactionAspect`](../../backend/identity/src/main/java/com/awap/identity/infrastructure/security/TenantContextTransactionAspect.java:19).
2. **A Hibernate `@Filter` named `tenantFilter` is added as defense-in-depth.** It adds a
   `tenant_id = :tenantId` predicate on tenant-scoped entities and is enabled/cleared from the same
   transaction aspect. If a query path misses the filter, RLS still blocks cross-tenant access.
3. The filter is applied incrementally to tenant-scoped entities, starting with `Task` and
   `WorkflowInstance`, and extended to the remaining entities as part of the coverage hardening.

## Consequences

- Positive: two independent layers; a missing JPA filter does not compromise isolation.
- Positive: unit tests can disable RLS and still assert the JPA filter isolates rows.
- Negative: minor query overhead (an extra `tenant_id` predicate), acceptable because the column is
  indexed.
- Negative: entity annotations must be kept in sync with new tenant-scoped entities.
