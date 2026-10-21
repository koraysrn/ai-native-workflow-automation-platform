# Repository Governance

This document defines the protection of the `main` branch, the issue/PR label taxonomy, the milestone structure and the merge policy as code. The idempotent apply script lives at [`scripts/apply-repo-governance.ps1`](../scripts/apply-repo-governance.ps1).

## Branch protection - `main`

| Rule | Value |
|---|---|
| Require pull request | Yes |
| Direct push | Disallowed |
| Enforce admins | Yes |
| Require conversation resolution | Yes |
| Dismiss stale reviews | Yes |
| Required approving review count | 1 |
| Require code owner reviews | No (see note below) |
| Allow force pushes | No |
| Allow deletions | No |
| Strict status checks | Yes |

> **Note - code owner review & required reviews:** Because the repository has a single maintainer (`@koraysrn`), `require_code_owner_reviews` is disabled and `required_approving_review_count` is targeted at 1. Enforcing a required review now would lock every PR, since the author cannot approve their own PR. These rules must be activated in GitHub once additional maintainers are added.

### Required status checks

- `Backend (Java 25 / Spring Boot 4.1.1)`
- `Frontend (TypeScript 7.x / Next.js 16)`
- `AI Layer (Python 3.15 / FastAPI)`
- `Version compatibility check`
- `Trivy vulnerability scan`
- `Secret scan (Gitleaks)`
- `OWASP Dependency Check`
- `SonarQube analysis`
- `Docker build (backend, frontend, ai-layer)`

## Merge policy

- Squash merge is mandatory; merge commits and rebase are disabled.
- The head branch is deleted automatically after merge.
- Commit messages must follow Conventional Commits ([`commitlint.config.cjs`](../commitlint.config.cjs)).

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
