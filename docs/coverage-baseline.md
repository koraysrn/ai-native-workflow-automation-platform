# Coverage Baseline

Measured from `mvn verify` (JaCoCo, `awap-identity` bundle) after the product-module
implementation (issue #97).

| Metric | Covered / Total | Percentage |
|--------|-----------------|------------|
| Line   | 1612 / 2917     | 55.3%      |
| Branch | 268 / 693       | 38.7%      |

> The denominator dropped versus the historical Phase-1 baseline because the JaCoCo
> gate excludes `config` / `interfaces.dto` / `IdentityApplication` classes.

## Target

Per [`CLAUDE.md`](../CLAUDE.md): line ≥ 80%, branch ≥ 95%.

## Ratchet plan (issue #96)

| Step | LINE minimum | BRANCH minimum | Note |
|------|-------------|----------------|------|
| 1 (current) | 0.55 | 0.35 | Measured-safe ratchet from 0.50; branch limit introduced. |
| 2 | 0.60 | 0.45 | After Auth/Tenant/WorkflowDefinition branch tests land. |
| 3 | 0.70 | 0.55 | After the negative/unhappy-path matrix (issue #93). |
| 4 | 0.80 | 0.60 | Heading toward the constitution target. |

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
