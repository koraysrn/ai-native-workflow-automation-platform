# ADR-0007: Named JPA-entity exception for the domain layer

- **Status:** Accepted
- **Date:** 2026-11-11
- **Owner:** `@koraysrn`
- **Issue:** #199
- **Review / expiry:** `2027-06-30`

## Context

The backend uses package-level Clean Architecture layers (ADR-001). The domain
layer must stay free of framework logic and point inward, but the persistence
model is annotation-driven: the aggregates are JPA entities and their mapping
lives on the class.

Issue #199 offers two choices: move to a pure domain plus persistence adapters,
or record an explicit, narrowly scoped exception. A full rewrite would touch 37
aggregates, every repository and every integration test at once, with a high risk
of data loss and behaviour regressions. The repository already documented the
exception informally in
[`docs/backend-module-boundaries.md`](../backend-module-boundaries.md); it was
not narrow (any domain class could add a JPA annotation) and had no owner, no
expiry and no inventory.

## Decision

The project takes the **documented exception** route for the current aggregates,
and makes it explicit and enforceable:

1. The exception is limited to the **named classes** in
   [`backend/identity/src/test/resources/architecture/jpa-domain-exception.txt`](../../backend/identity/src/test/resources/architecture/jpa-domain-exception.txt).
   There are 37 entries today, one per JPA-annotated domain class.
2. A domain class **outside** that list may not import `jakarta.persistence.*`,
   `org.hibernate.annotations.*` or `org.springframework.data.annotation.*`.
   [`ModularArchitectureTest`](../../backend/identity/src/test/java/com/awap/architecture/ModularArchitectureTest.java)
   fails the build.
3. The exception covers **mapping annotations only**. Persistence machinery
   (`EntityManager`, criteria queries, repositories, Hibernate APIs beyond
   annotations) stays forbidden in every domain package.
4. Domain behaviour stays in the domain classes. Repositories, transactions and
   web mapping remain in `infrastructure` and `interfaces`.
5. [`JpaDomainExceptionTest`](../../backend/identity/src/test/java/com/awap/architecture/JpaDomainExceptionTest.java)
   compares the named list with the real annotated classes, so both a new entity
   and a stale entry fail the build. The same test fails after the expiry date.

## Scope and inventory

The exception covers the entities whose mapping is annotation-driven:

- AI: `AutomationAudit`
- Analytics: `AnalyticsAggregate`
- Document: `DocumentVersion`
- Engine: `ExecutionContext`, `ExecutionLog`, `NodeExecution`, `OutboxEvent`,
  `ReplayRun`, `SagaInstance`, `SagaStep`, `WorkflowInstance`
- File: `FileRecord`, `FileScanAudit`
- Form: `FormDefinition`, `FormSubmission`
- Identity: `IdempotencyRecord`, `Invitation`, `RefreshToken`, `Tenant`, `User`
- Notification: `NotificationDelivery`, `NotificationTemplate`,
  `NotificationTemplateVersion`
- Organization: `Company`, `Department`, `OrganizationAuditEvent`,
  `OrganizationUser`, `Position`, `Team`
- Task: `Task`
- Usage: `TenantUsageLimit`, `UsageCounter`
- Workflow: `NodeDefinition`, `TransitionDefinition`, `WorkflowDefinition`,
  `WorkflowVariable`, `WorkflowVersion`

The machine-readable source of truth is the classpath file above; this list is
its human-readable inventory.

## Migration plan (aggregate by aggregate)

The exception is a debt, not the target architecture. Each aggregate follows the
same contained migration:

1. Add a persistence entity in `infrastructure/persistence` with the JPA
   mapping and a package-private constructor.
2. Add a MapStruct mapper between the domain object and the persistence entity.
3. Move repository access behind a domain-facing port implemented in
   `infrastructure`.
4. Add a round-trip test and a transaction/rollback test for the aggregate.
5. Remove the JPA annotations and the class name from the exception file in the
   same PR.

Order: Task, Form, Notification, Organization (small aggregates first), then
Workflow, Engine and Identity. `DomainPersistenceRoundTripTest` guards the
current mapping until each aggregate moves.

## Consequences

- The exception is explicit, owned and time-boxed; it cannot grow silently.
- A new JPA-annotated domain class requires an architecture review and a new
  line in the inventory.
- The domain keeps its behaviour and the persistence model keeps working while
  the migration proceeds.
- The expiry date forces a review: either migrate the remaining aggregates or
  renew this ADR with a new date and a written reason.
