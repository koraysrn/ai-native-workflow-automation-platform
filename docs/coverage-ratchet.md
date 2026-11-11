# Coverage ratchet (issue #196)

The project target is line ≥ 80% and branch ≥ 95%. The JaCoCo bundle gate used to
enforce only 55% line / 35% branch, which allowed large uncovered areas to merge.
This document records the measured baseline, the ratchet steps, target dates,
ownership and the enforcement mechanics.

## Ownership

- **Owner:** @koraysrn (single maintainer).
- **Review cadence:** first Monday of every month; the trend artifact and
  `docs/coverage/trend.json` are compared and the next step is scheduled.
- **Who approves a step:** the owner opens the ratchet PR (or activates the Maven
  profile), states the new measured baseline, and treats a red gate as a blocker.
- **Escalation:** if a step cannot be reached by its date, the owner records the
  gap and the remediation plan in this file; the step does not silently slip.

## Measured baseline (2026-11-10)

| Module | Line | Branch | Gated |
|--------|------|--------|-------|
| `awap-identity` (bundle, exclusions applied) | 85.72% (8758/10206) | 71.93% (2196/3059) | Yes, 80/60 |
| `awap-common` (shared kernel) | 96.75% (149/160) | 84.38% (27/32) | Trend only |
| `com.awap.organization` packages | 100% (350/350) | 100% (34/34) | Yes, class and package 95/90 |

The full per-package snapshot is committed in
[`docs/coverage/trend.json`](coverage/trend.json) and
[`docs/coverage/trend-common.json`](coverage/trend-common.json).

The recorded bundle baseline is deliberately floored below the local measurement
(identity 85.50% / 71.50%, common 96.50% / 84.00%) to absorb small environment
variance while still failing any meaningful regression. The active JaCoCo minimum
(80% line / 60% branch) remains the hard gate.

## Ratchet steps

| Step | Bundle line | Bundle branch | Status | Target date | Activation |
|------|-------------|---------------|--------|-------------|------------|
| 1 | 0.80 | 0.60 | **Active** | 2026-11-10 | default properties |
| 2 | 0.80 | 0.75 | Planned | 2027-01-31 | `-Pcoverage-step2` / default properties |
| 3 | 0.80 | 0.90 | Planned | 2027-04-30 | `-Pcoverage-step3` / default properties |

Step 2 needs roughly 3.1 branch points (about 94 branches) above the current 71.93%.
Step 3 reaches the constitution branch target. Dates are reviewed monthly and can
only move earlier without a written exception here.

The minimums are Maven properties in
[`backend/pom.xml`](../backend/pom.xml):

```xml
<coverage.bundle.line.min>0.80</coverage.bundle.line.min>
<coverage.bundle.branch.min>0.60</coverage.bundle.branch.min>
```

`coverage-step2` and `coverage-step3` profiles override only the branch minimum.
The CI log prints the active values in the `Report coverage minimums` step.

## Class-level gates

Critical classes must stay at line ≥ 95% / branch ≥ 90%. In addition to the
existing notification, workflow-expression, saga, organization and position gates,
issue #196 added:

- `com.awap.ai.application.AiBudgetGuard`
- `com.awap.ai.application.AiNodeService`
- `com.awap.file.application.FileScanEventBus`
- `com.awap.webhook.infrastructure.WebhookVerifier`
- `com.awap.engine.application.NodeTimeoutHandler`

## Changed-line coverage

[`scripts/check-changed-line-coverage.mjs`](../scripts/check-changed-line-coverage.mjs)
intersects added Java lines from `git diff <base>...HEAD` with the JaCoCo XML
report. A changed executable line without coverage fails the build at the 100%
threshold. Generated code and DTO/config paths are excluded by explicit patterns:

- `**/interfaces/dto/**`
- `**/config/**`
- `**/generated-sources/**`
- `**/*MapperImpl.java`
- `**/IdentityApplication.java`

A changed Java file missing from the JaCoCo report is treated as uncovered, so a
new production class cannot bypass the check by never being loaded.

## Generated baseline document (issue #197)

[`scripts/coverage-baseline.mjs`](../scripts/coverage-baseline.mjs) renders
[`docs/coverage-baseline.md`](coverage-baseline.md) from the JaCoCo CSV **and**
XML reports. It cross-checks both formats, applies the bundle exclusions, lists
every module aggregate and every class-level gate, and embeds a machine-readable
copy of the values for the PR delta.

The document is generated; never edit it by hand. Regenerate it after a clean
build (JaCoCo appends to `target/jacoco.exec`, so an incremental `verify` can
report lines that the current run did not execute):

```bash
cd backend && ./mvnw -B clean verify
cd ..
make coverage-baseline
```

CI fails when the committed document drifts:

```bash
make coverage-baseline-check
```

The `Coverage baseline drift` CI step runs the same check, uploads the
`coverage-baseline` artifact and posts the delta as a PR comment. The generator's
tests (`scripts/test/coverage-baseline.test.mjs`) cover a stale document, a
missing report, a malformed CSV, a CSV/XML mismatch and a module without classes.

## Trend report and regression gate

[`scripts/coverage-trend.mjs`](../scripts/coverage-trend.mjs) aggregates the JaCoCo
CSV into the bundle and per-package ratios, compares them with the committed
baseline and the active minimum, and fails when either the minimum or the recorded
value regresses. CI publishes the current snapshot as the `coverage-trend`
artifact and writes a table to the job summary.

Update the baseline only after an intentional, reviewed improvement:

```bash
cd backend
./mvnw -B verify
node ../scripts/coverage-trend.mjs \
  --csv identity/target/site/jacoco/jacoco.csv \
  --baseline ../docs/coverage/trend.json \
  --write
node ../scripts/coverage-trend.mjs \
  --csv common/target/site/jacoco/jacoco.csv \
  --baseline ../docs/coverage/trend-common.json \
  --write
```

## Gate self-tests

The gate scripts are covered by tests that deliberately feed uncovered and
regressed input:

- `scripts/test/check-changed-line-coverage.test.mjs` — fully covered lines pass,
  an uncovered changed line fails, a missing JaCoCo entry fails, and generated
  files are excluded.
- `scripts/test/coverage-trend.test.mjs` — meeting the baseline passes, dropping
  below the minimum fails, and a regression above the minimum still fails.
- `scripts/test/coverage-baseline.test.mjs` — a matching document passes, a stale
  or missing document fails, and malformed, missing or mismatched reports fail.

CI runs these tests in the Backend job before the gates, and uploads the
`jacoco-report`, `coverage-trend` and `pit-report` artifacts.
