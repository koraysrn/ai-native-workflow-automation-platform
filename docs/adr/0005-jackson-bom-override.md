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
5. Trivy/OWASP remain the regression gate: the same CI run fails if a HIGH/CRITICAL Jackson CVE is
   reported again. No additional dashboard is required because the failure blocks the PR.

## Removal checklist

1. Confirm the Spring Boot release notes/BOM manage `jackson-2-bom >= 2.21.7` and
   `jackson-bom >= 3.1.7` (check `mvn help:evaluate -Dexpression=jackson-2-bom.version`).
2. Delete `jackson-2-bom.version` and `jackson-bom.version` from `backend/pom.xml`.
3. Run `backend/scripts/check-jackson-versions.sh` and `mvn -B verify` — both must pass without the
   override.
4. Run Trivy and OWASP Dependency Check; no HIGH/CRITICAL Jackson findings.
5. Update this ADR status to `Superseded`, remove the floor check and note the removal in
   `COMPATIBILITY.md`.

## Consequences

- The floor check adds a few seconds to the backend job but makes the override lifecycle explicit.
- A future Jackson 3.2.x upgrade passes automatically as long as it stays above the floor.
- If the floor check fails, the message points to this ADR and the required version.
