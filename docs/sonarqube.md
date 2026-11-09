# SonarQube quality gate

Issue #182 makes SonarQube a **mandatory merge gate**. The `Code Quality` job in
[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) is a required status
check and fails when:

- `SONAR_TOKEN` or `SONAR_HOST_URL` is missing;
- the JaCoCo XML report is missing or empty;
- the quality gate (`sonar.qualitygate.wait=true`) does not pass;
- the scanner or server is unreachable.

There is no silent "skip when secrets are absent" path.

## One-time bootstrap escape hatch

A fresh repository cannot pass the gate before a SonarQube server exists. The
maintainer-only **repository variable** `SONAR_BOOTSTRAP=true` is the explicit
bootstrap switch:

- it emits a `::warning` annotation naming the missing secrets;
- it sets `SONAR_SKIP=true` for that run;
- it must be deleted immediately after the two secrets are configured.

It is intentionally not a secret and not a default. Without the variable, a
missing secret is a hard failure with an actionable message. Do not leave
`SONAR_BOOTSTRAP` set after bootstrap.

## Required repository configuration

| Name | Kind | Purpose |
|---|---|---|
| `SONAR_TOKEN` | Repository secret | User token with *Execute analysis* permission |
| `SONAR_HOST_URL` | Repository secret | SonarQube/SonarCloud URL, e.g. `https://sonarcloud.io` |

The project identity lives in the `<properties>` section of
[`backend/pom.xml`](../backend/pom.xml) and is the single source of truth: CI
reads `sonar.projectKey` from the pom instead of duplicating it in the workflow.

The SonarScanner for Maven reads analysis parameters from the pom. It does **not**
read `sonar-project.properties`, which is a file for the standalone scanner. That
is why a `sonar-project.properties` file is not committed here: it would look
authoritative while being silently ignored.

- `sonar.organization` is mandatory on **SonarQube Cloud** and ignored by a
  self-hosted server. A missing value fails the scanner with
  `You must define the following mandatory properties: sonar.organization`.
- `sonar.projectKey` must match the project that is bound to this repository
  (`koraysrn_ai-native-workflow-automation-platform` on SonarQube Cloud). A key
  for a project that does not exist or is not writable by the token fails the
  analysis before the gate is evaluated.
- `sonar.sources` / `sonar.tests` stay unset so Maven infers the scope per
  module; setting root-relative paths would be applied to every module.

Bind that project to a quality gate and enable the SonarQube GitHub application
for PR decoration. The CI job passes `sonar.pullrequest.key`,
`sonar.pullrequest.base` and `sonar.pullrequest.branch` on pull-request events
so the analysis lands on the PR.

> **Automatic Analysis must be off.** SonarQube Cloud refuses a CI scan while the
> project still uses GitHub Automatic Analysis ("You are running CI analysis
> while Automatic Analysis is enabled"). Disable it under *Administration →
> Analysis Method → Automatic Analysis* before the first CI run, otherwise the
> gate can never pass.

## Coverage import

JaCoCo writes XML reports to:

```
common/target/site/jacoco/jacoco.xml
identity/target/site/jacoco/jacoco.xml
```

`sonar.coverage.jacoco.xmlReportPaths` in
[`backend/pom.xml`](../backend/pom.xml) declares both paths. Before scanning, CI
runs [`scripts/check-jacoco-report.mjs`](../scripts/check-jacoco-report.mjs); an
empty or missing report fails the job with an actionable message instead of
silently reporting 0% coverage.

## Local scanner

```bash
cd backend
mvn -B -pl identity -am test jacoco:report
node ../scripts/check-jacoco-report.mjs identity/target/site/jacoco/jacoco.xml

SONAR_HOST_URL="${SONAR_HOST_URL:?set the SonarQube URL}" \
SONAR_TOKEN="${SONAR_TOKEN:?set a SonarQube token}" \
mvn -B org.sonarsource.scanner.maven:sonar-maven-plugin:5.8.0.7211:sonar \
  -Dsonar.host.url="$SONAR_HOST_URL" \
  -Dsonar.token="$SONAR_TOKEN" \
  -Dsonar.qualitygate.wait=true
```

The project key and organization come from `backend/pom.xml`, so the local
command scans exactly the same project as CI. `-Dsonar.qualitygate.wait=true` is
already a pom property and can be omitted locally for a non-blocking run.

The scanner version is pinned in CI (`SONAR_MAVEN_PLUGIN_VERSION`) and in this
document. Upgrade it through Renovate together with the SonarQube server
compatibility check.

## Bootstrap procedure (maintainers)

```bash
# 1. Add the two secrets (values are never printed).
gh secret set SONAR_HOST_URL --body "https://sonarcloud.io"
gh secret set SONAR_TOKEN --body "<token>"

# 2. Remove the bootstrap escape hatch.
gh variable delete SONAR_BOOTSTRAP

# 3. Push a trivial change and confirm Code Quality runs the scanner (not a skip).
```

Until step 2 runs, every PR shows an explicit warning annotation instead of a
hard failure. That warning is the audit trail for the bootstrap period.

## Verifying the gate blocks a merge

1. Open a PR with a deliberately new blocker/critical issue.
2. Confirm `Code Quality` fails and the required-check list marks the PR as
   blocked.
3. Remove the issue and confirm the job turns green.

## Threshold ownership

Thresholds live in the SonarQube quality gate, not in this repository. Review
them quarterly together with the coverage baseline in
[`docs/coverage-baseline.md`](coverage-baseline.md).
