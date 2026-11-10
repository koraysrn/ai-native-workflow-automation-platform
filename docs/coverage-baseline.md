# Coverage Baseline

Current measured baseline from `mvn verify` (JaCoCo, 2026-11-10) after the
organization coverage work in issue #195.

| Module | Metric | Covered / Total | Percentage |
|--------|--------|-----------------|------------|
| `awap-identity` (gated bundle) | Line | 8758 / 10206 | 85.72% |
| `awap-identity` (gated bundle) | Branch | 2196 / 3059 | 71.93% |
| `awap-common` (trend only) | Line | 149 / 160 | 93.12% |
| `awap-common` (trend only) | Branch | 27 / 32 | 84.38% |

> The denominator excludes `config` / `interfaces.dto` / `IdentityApplication`
> classes for the gated identity bundle, matching the JaCoCo rule.

## Target

Per [`CLAUDE.md`](../CLAUDE.md): line ≥ 80%, branch ≥ 95%. The active ratchet and
target dates are in [`docs/coverage-ratchet.md`](coverage-ratchet.md); step 1 now
enforces 80% line / 60% branch (previously 55/35).

## Historical ratchet (issue #96)

| Step | LINE minimum | BRANCH minimum | Note |
|------|-------------|----------------|------|
| 1 (superseded) | 0.55 | 0.35 | Measured-safe ratchet from 0.50; branch limit introduced. |
| 2 (superseded) | 0.60 | 0.45 | After Auth/Tenant/WorkflowDefinition branch tests land. |
| 3 (superseded) | 0.70 | 0.55 | After the negative/unhappy-path matrix (issue #93). |
| 4 (active, raised) | 0.80 | 0.60 | Issue #196 step 1. |

`scripts/jacoco-rank.sh` parses `target/site/jacoco/jacoco.csv` and lists the
lowest-covered classes so each ratchet step targets the right services.

## Mutation testing (PIT)

Measured with `./mvnw -pl identity -Ppit org.pitest:pitest-maven:mutationCoverage`
over the critical domain classes (`Task`, `WorkflowInstance`, `NodeExecution`,
`WorkflowDefinition`, `FormDefinition`, `Tenant`, `User`, `OutboxEvent`).

| Metric | Value |
|--------|-------|
| Mutation score | 85% (113 killed / 133 generated) |
| Line coverage (mutated classes) | 93% (298 / 319) |
| Test strength | 88% |
| Mutations with no coverage | 4 |
| Threshold (mutation) | 70% |
| Threshold (coverage) | 80% |

The `mutationThreshold`/`coverageThreshold` gates live in the `pit` profile of
[`backend/pom.xml`](../backend/pom.xml); the incremental-history config was removed
because no history plugin was on the classpath.

## Notification read-state PIT (issue #157)

Measured with
`./mvnw -pl identity -am -Ppit-notification-read test-compile org.pitest:pitest-maven:mutationCoverage`.

| Metric | Value |
|--------|-------|
| Mutation score | 100% (76 killed / 76 generated) |
| Line coverage (mutated classes) | 95% (235 / 247) |
| Test strength | 100% |
| Mutations with no coverage | 0 |
| Threshold (mutation) | 85% |
| Threshold (coverage) | 90% |

The profile mutates the read/unread transitions, tenant authorization, unread
listing/count adapter, retention policy and purge scheduler. It runs in CI as the
`Notification read mutation testing` step and writes
`identity/target/pit-reports-notification-read`, which is uploaded with the other
PIT reports. The `NotificationDelivery` delivery/retry state machine is excluded
from this profile because it has its own focused coverage; only read-state members
are in scope.

## Organization module gate (issue #195)

The organization module is a critical module, protected by a package-level JaCoCo
rule and a dedicated mutation profile.

### Class inventory

| Layer | Classes | Covered branches |
|-------|---------|------------------|
| Domain | `Company`, `Department`, `Team`, `Position`, `OrganizationUser`, `OrganizationAuditEvent` | Entity factories, getters, soft-delete flag, `Position.changeRole` (null/valid), role default, `OrganizationUser.assignToTeam` |
| Application | `OrganizationService` | Null/blank caller role, escalation 403, unknown caller role 403, null role 400, same-role no-op, role propagation with/without an identity account, foreign tenant 404, duplicate 409 for company/department/team/position/user, quota 429, every tree/list/create path |
| Web | `OrganizationController` | 200/201/400/401/403/404/409/429, `USER_MANAGE` enforcement, missing tenant/role/user context |
| DTOs | `*Request`, `*Response`, `OrganizationMapperImpl` | Excluded from the gate (generated mapping records) |

### Measured result

| Metric | Covered / Total | Percentage |
|--------|-----------------|------------|
| Package line | 350 / 350 | 100% |
| Package branch | 34 / 34 | 100% |
| PIT mutation score (`pit-organization`) | 47 killed / 51 generated | 92% |
| PIT threshold (mutation / coverage) | 80% / 80% | passed |

The rule lives in [`backend/pom.xml`](../backend/pom.xml) as a `PACKAGE` rule with
`LINE` ≥ 0.95 and `BRANCH` ≥ 0.90. The `pit-organization` profile mutates
`com.awap.organization.domain.*` and runs in CI as
`Organization mutation testing (PIT >= 80%)`.

### Integration and artifacts

- [`OrganizationHierarchyIntegrationITest`](../backend/identity/src/test/java/com/awap/organization/application/OrganizationHierarchyIntegrationITest.java)
  exercises PostgreSQL RLS, tenant-scoped reads, foreign-parent 404, duplicate 409,
  soft-delete filtering and audit retention with Testcontainers.
- [`OrganizationControllerWebMvcTest`](../backend/identity/src/test/java/com/awap/organization/interfaces/api/OrganizationControllerWebMvcTest.java)
  covers the 200/201/400/401/403/404/409 paths.
- CI uploads the JaCoCo report as the `jacoco-report` artifact and the PIT reports
  (including `pit-reports-organization`) as the `pit-report` artifact.
