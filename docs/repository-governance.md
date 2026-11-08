# Repository Governance

This document defines the protection of the `main` branch, the issue/PR label taxonomy, the milestone structure and the merge policy as code. The policy is enforced by the idempotent [`scripts/apply-branch-protection.sh`](../scripts/apply-branch-protection.sh): `--apply` creates or updates the protection, `--verify` fails on drift and `--dry-run` prints the planned calls without changing GitHub. A scheduled [`branch-protection-drift.yml`](../.github/workflows/branch-protection-drift.yml) job reads the live settings and fails when they diverge. [`settings/branch-protection.json`](../settings/branch-protection.json) is a generated snapshot of `--expected`; regenerate it with `scripts/apply-branch-protection.sh --expected > settings/branch-protection.json` and let the test suite prove it is in sync.

## Branch protection - `main`

| Rule | Value |
|---|---|
| Require pull request | Yes |
| Direct push | Disallowed |
| Enforce admins | Yes |
| Require conversation resolution | Yes |
| Dismiss stale reviews | Yes |
| Required approving review count | 1 |
| Require code owner reviews | No (single-maintainer exception, see below) |
| Allow force pushes | No |
| Allow deletions | No |
| Strict status checks | Yes |

### Required status checks

The names are defined once in the `REQUIRED_CHECKS` array of [`apply-branch-protection.sh`](../scripts/apply-branch-protection.sh). The script validates every name against the job names in [`ci.yml`](../.github/workflows/ci.yml) and [`version-compatibility.yml`](../.github/workflows/version-compatibility.yml), and against the per-matrix contexts derived from [`docker-build.yml`](../.github/workflows/docker-build.yml); an unknown name or an unlisted unconditional job/matrix entry fails the run, so renaming or reshaping a workflow cannot leave a PR permanently pending.

- `Backend`
- `Frontend`
- `AI Layer`
- `Verify Versions`
- `Vulnerability Scan`
- `Secret Scan`
- `Dependency Scan`
- `Code Quality`
- `Docker Build / Build Images (awap-backend, backend/Dockerfile, .)`
- `Docker Build / Build Images (awap-frontend, frontend/Dockerfile, .)`
- `Docker Build / Build Images (awap-ai-layer, ai-layer/Dockerfile, .)`
- `End-to-End Tests`
- `API Lint`
- `Observability Checks`
- `CDC Checks`
- `Load Tests`
- `Branch Protection`
- `Release Checks`

`Contract Verification` (`pact-gate`) is intentionally not required: it only runs once `PACT_BROKER_BASE_URL` is configured, so requiring it would leave every PR pending on a repository without a Pact Broker. The reusable Docker workflow reports one context per matrix entry, so the three expanded names above are required instead of the bare `Docker Build` caller job; the script derives them from [`docker-build.yml`](../.github/workflows/docker-build.yml) and fails if the matrix changes without the policy following.

### Single-maintainer exception (explicit, audited)

The repository currently has one maintainer (`@koraysrn`). GitHub never counts a self-approval, so `required_approving_review_count` is kept at 1 for the policy but the only maintainer cannot satisfy it on their own PR. The exception is explicit and must not be silently weakened:

- Do **not** permanently set the approval count to 0, disable admin enforcement, remove required checks or delete the protection.
- Break-glass is a temporary, audited change documented in runbook [O22](operations-runbook.md#o22--branch-protection-drift-and-break-glass-issue-178): open an incident ticket, lower only the approval count, merge the urgent PR, then restore the policy immediately with `--apply` and prove it with `--verify`.
- The scheduled drift job fails while the exception is active. That failure is the reminder to restore protection; do not mute it.
- When a second maintainer is added, enable `require_code_owner_reviews` and remove this exception in the same policy change.
- Automation that must merge without an interactive review (for example release automation) should use a dedicated GitHub App with the minimum required permissions. Never disable protection globally to unblock a bot.

### Drift detection

[`branch-protection-drift.yml`](../.github/workflows/branch-protection-drift.yml) runs daily at 06:00 UTC, on `workflow_dispatch`, and on pushes that change the workflow, the script, its tests or the policy snapshot. It uses the `BRANCH_PROTECTION_TOKEN` repository secret (repository admin: read and write; a classic token needs the `repo` scope) and runs:

```sh
scripts/apply-branch-protection.sh --verify --repo owner/repo
```

A divergence prints one `DRIFT <field>: expected ..., got ...` line per differing field and fails the job. The failure summary points at `--apply` and O22.

## Merge policy

- Squash merge is mandatory; merge commits and rebase are disabled.
- The head branch is deleted automatically after merge.
- Commit messages must follow Conventional Commits ([`commitlint.config.cjs`](../commitlint.config.cjs)).
  The required `Release Checks` job runs commitlint over every commit in the push/PR range,
  not only over the commit being authored, and validates the pull request title because a
  squash merge turns it into the final `main` commit message.
- Releases are produced by release-please from Conventional Commits. The bot opens a release
  pull request with the version bump and the `CHANGELOG.md` entry; it is reviewed and tested
  like any other PR and the tag is created only after it merges and the full CI run on `main`
  is green. `CHANGELOG.md` is bot-owned: a human edit fails the changelog guard. See the
  [release runbook](release-runbook.md).

## Labels

| Label | Description |
|---|---|
| `kind/bug` | Bug fix or correction |
| `kind/feature` | New feature or request |
| `kind/chore` | Maintenance, tooling or infra work |
| `area/backend` | Backend (Java / Spring Boot) |
| `area/frontend` | Frontend (Next.js / React) |
| `area/ai` | AI layer (Python / FastAPI) |
| `priority/p0` | Critical - must ship first |
| `priority/p1` | High priority |
| `priority/p2` | Medium priority |
| `priority/p3` | Low priority |
| `phase/preparation` | Preparation phase (repo, CI/CD, security foundations) |
| `phase/core-mvp` | Core MVP phase |
| `phase/expansion` | Post-MVP expansion |

> **Note - phase naming:** The planning documents originally used `phase/on-faz`, `phase/faz-0` and `phase/faz-1`. These were renamed to `phase/preparation`, `phase/core-mvp` and `phase/expansion` for clarity and consistency with the milestone names.

## Milestones

| Milestone | Scope |
|---|---|
| `Preparation Phase` | Repo, CI/CD and security foundations |
| `Phase 0 - Core MVP` | Core MVP |
| `Phase 1 - Expansion` | Post-MVP expansion |
