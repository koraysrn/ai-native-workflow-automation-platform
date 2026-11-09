# ADR-0005 — Jackson BOM security override and its removal condition

- **Status:** Accepted
- **Date:** 2026-10-27
- **Context:** Issue #136, `backend/pom.xml` properties `jackson-2-bom.version=2.21.7` and
  `jackson-bom.version=3.1.7`

## Context

Spring Boot 4.1.1's managed Jackson BOMs resolve to versions affected by
CVE-2026-89407, CVE-2026-89425, CVE-2026-68497, CVE-2026-91776 and CVE-2026-91777. The build pins
`jackson-2-bom.version` (Jackson 2) and `jackson-bom.version` (Jackson 3) above the Boot-managed
versions to close them.

An untracked override is risky in both directions: it can drift (a Renovate BOM bump silently
overridden) or be removed too early (CVE reintroduced).

## Decision

1. Keep both overrides until **Spring Boot's own BOM manages at least 2.21.7 (Jackson 2) and 3.1.7
   (Jackson 3)**.
2. Guard the floor in CI on every PR:
   [`backend/scripts/check-jackson-versions.sh`](../../backend/scripts/check-jackson-versions.sh)
   prints the resolved versions and fails below the floor (`dependency:list`, so it checks the real
   classpath rather than the property text).
3. Keep behavioural compatibility covered by
   [`JacksonCompatibilityTest`](../../backend/identity/src/test/java/com/awap/architecture/JacksonCompatibilityTest.java):
   UUID/Instant round-trips, tolerant unknown-field reads, strict-mode rejection, no polymorphic
   default typing and the Avro event payload shape.
4. Renovate groups Maven upgrades (`backend-maven`); the Jackson group is described in
   [`renovate.json`](../../renovate.json) so the BOM bump and the override are reviewed together.
5. Use a **CI-only Trivy regression signal** (decided in issue #162) instead of a
   Grafana/Prometheus dashboard. Trivy runs inside GitHub Actions and its findings are not scraped
   by Prometheus; a Pushgateway just for CI would add a production dependency and a stale-metric
   failure mode. The signal is:
   - a job summary and PR annotations with the CVE severity breakdown, produced by
     [`scripts/trivy-regression-report.mjs`](../../scripts/trivy-regression-report.mjs);
   - the raw JSON report uploaded as a workflow artifact for history;
   - one tracking issue opened after two consecutive failing `main` runs and closed when the gate
     recovers, driven by
     [`scripts/trivy-regression-notify.mjs`](../../scripts/trivy-regression-notify.mjs).

   OWASP Dependency Check remains a separate gate; Trivy stays merge-blocking.
6. Scan the **shipped dependency graph, not `pom.xml`**. A pom is not a lock file: Trivy's static
   parser cannot resolve the Spring Boot BOM imports, so it reported `jackson-databind 2.19.1` for a
   build that resolves `2.21.7` and missed transitive components. The CI job therefore generates the
   aggregate CycloneDX SBOM (`makeAggregateBom`) and scans that, while the filesystem scan skips
   `**/pom.xml`.
7. Keep the remaining transitive floors in `backend/pom.xml` next to the Jackson overrides:
   guava `33.7.2-jre`, snappy-java `1.1.10.9`, lz4-java `1.11.4` and tomcat-embed `11.0.25`. Each
   floor is visible in the SBOM, so the Trivy gate fails if one of them disappears or regresses.

## Trivy regression signal (issue #162)

The OWASP and Trivy CI jobs are the regression alarm, but a failed PR alone disappears from the
team's view once the branch is fixed. Issue #162 therefore makes the alarm visible without adding
CI metrics to the production monitoring stack:

| Concern | Decision |
|---|---|
| Data source | Trivy JSON produced by the CI `trivy` job; no Pushgateway and no Prometheus scrape. |
| Per-run visibility | `$GITHUB_STEP_SUMMARY` (severity counts, fixable counts and the gated findings) plus one `::error` annotation per gated finding. |
| History | `trivy-results.json` is uploaded as a workflow artifact. |
| Recurrence | After **two consecutive failing runs on `main`**, the job opens or updates a single tracking issue; a green `main` run comments and closes it. |
| Merge gate | The report script exits non-zero when a `CRITICAL`/`HIGH` finding exists, so the required `Vulnerability Scan` check still blocks merges. |

Why not Grafana/Prometheus: the existing dashboard and alert rules only cover runtime Micrometer
metrics and CDC exporter series ([`metric-catalog.json`](../../infra/grafana/metric-catalog.json)).
CI results have no scrape target; exporting them would require a Pushgateway (or a new metrics API)
and a lifecycle job to avoid stale series after a workflow is deleted. The CI-only signal keeps the
same source of truth as the gate and adds no infrastructure.

Operating instructions, including the Jackson override floor checks, are in
[`operations-runbook.md`](../operations-runbook.md#o21--trivy-regression-signal-issue-162).

## Removal checklist

1. Confirm the Spring Boot release notes/BOM manage `jackson-2-bom >= 2.21.7` and
   `jackson-bom >= 3.1.7` (check `mvn help:evaluate -Dexpression=jackson-2-bom.version`).
2. Delete `jackson-2-bom.version` and `jackson-bom.version` from `backend/pom.xml`.
3. Run `backend/scripts/check-jackson-versions.sh` and `mvn -B verify` — both must pass without the
   override.
4. Run Trivy and OWASP Dependency Check; the `Vulnerability Scan` summary/annotations must
   show no HIGH/CRITICAL Jackson findings, and OWASP must be clean.
5. Update this ADR status to `Superseded`, remove the floor check and note the removal in
   `COMPATIBILITY.md`.

## Consequences

- The floor check adds a few seconds to the backend job but makes the override lifecycle explicit.
- A future Jackson 3.2.x upgrade passes automatically as long as it stays above the floor.
- If the floor check fails, the message points to this ADR and the required version.
