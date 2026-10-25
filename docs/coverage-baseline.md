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
