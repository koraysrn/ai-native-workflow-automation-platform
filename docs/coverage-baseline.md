# Coverage Baseline

Recorded on the `chore/phase-1-preflight-hardening` branch after the security and
unit-test work (commit `ab382b7`), from `mvn verify` (JaCoCo, `awap-identity` bundle).

| Metric | Covered / Total | Percentage |
|--------|-----------------|------------|
| Line   | 6169 / 11627    | 53.1%      |
| Branch | 268 / 683       | 39.2%      |

## Target

Per [`CLAUDE.md`](../CLAUDE.md): line ≥ 80%, branch ≥ 95%. The JaCoCo gate is
currently ratcheted at 0.40 (line) to keep CI green while the remaining test plan
(BÖLÜM 2/3) is implemented. The ratchet must be raised incrementally (0.40 → 0.60 →
0.80) as coverage grows.

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
