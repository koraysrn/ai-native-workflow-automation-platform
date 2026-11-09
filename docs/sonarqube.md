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

Bind the `awap` project to a quality gate in SonarQube and enable the SonarQube
GitHub application for PR decoration. The CI job passes
`sonar.pullrequest.key`, `sonar.pullrequest.base` and
`sonar.pullrequest.branch` on pull-request events so the analysis lands on the
PR.

## Coverage import

JaCoCo writes XML reports to:

```
common/target/site/jacoco/jacoco.xml
identity/target/site/jacoco/jacoco.xml
```

`backend/sonar-project.properties` declares both paths. Before scanning, CI runs
[`scripts/check-jacoco-report.mjs`](../scripts/check-jacoco-report.mjs); an empty
or missing report fails the job with an actionable message instead of silently
reporting 0% coverage.

## Local scanner

```bash
cd backend
mvn -B -pl identity -am test jacoco:report
node ../scripts/check-jacoco-report.mjs identity/target/site/jacoco/jacoco.xml

SONAR_HOST_URL="${SONAR_HOST_URL:?set the SonarQube URL}" \
SONAR_TOKEN="${SONAR_TOKEN:?set a SonarQube token}" \
mvn -B org.sonarsource.scanner.maven:sonar-maven-plugin:5.8.0.7211:sonar \
  -Dsonar.projectKey=awap \
  -Dsonar.host.url="$SONAR_HOST_URL" \
  -Dsonar.token="$SONAR_TOKEN" \
  -Dsonar.qualitygate.wait=true
```

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
