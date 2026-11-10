# Release Runbook

This runbook covers the release flow required by issue #179: releases are produced by a bot
from Conventional Commits, the version bump and `CHANGELOG.md` entry are reviewable, and a tag
is created only after the release pull request is merged and the full CI run on `main` is green.

## Flow

1. A pull request with Conventional Commit messages is merged to `main`.
2. CI runs on the merge commit and the `Release Checks` job lints every commit in the range.
3. When the whole `CI` workflow succeeds, the [`Release`](../.github/workflows/release.yml)
   workflow runs through `workflow_run` and release-please opens or updates the release PR.
4. The release PR contains the version bump (`package.json`), the `.release-please-manifest.json`
   update and the generated `CHANGELOG.md` entry. It is reviewed and the full CI runs on it.
5. Merging the release PR pushes to `main`; the resulting green `CI` run lets release-please
   create the tag and the GitHub release. The release job then dispatches
   [`cd.yml`](../.github/workflows/cd.yml) with the released tag.

`CHANGELOG.md` is owned by the bot. A human pull request that touches it fails the
`Release Checks` job (`scripts/release/changelog-guard.mjs`). Fix the change by reverting the
manual edit and letting the release PR regenerate the entry.

## Configuration

| File | Purpose |
|---|---|
| [`release-please-config.json`](../release-please-config.json) | Monorepo package, changelog sections, release PR title. |
| [`.release-please-manifest.json`](../.release-please-manifest.json) | Last released version per package. |
| [`release.yml`](../.github/workflows/release.yml) | Release PR flow, CI gate, tag creation, CD dispatch. |
| `scripts/release/dry-run.mjs` | Prints the next version and changelog entries. |
| `scripts/release/lint-commits.mjs` | commitlint over the whole push/PR range. |
| `scripts/release/changelog-guard.mjs` | Rejects manual `CHANGELOG.md` edits. |
| `scripts/release/token-preflight.mjs` | Actionable permission failure before release-please runs. |
| `scripts/release/verify-ci-gate.mjs` | Re-checks event, branch, conclusion and commit SHA. |

### Token

The release job uses a dedicated bot identity. The workflow supports either:

- **GitHub App (preferred):** set the `RELEASE_PLEASE_APP_ID` and `RELEASE_PLEASE_PRIVATE_KEY`
  repository secrets. The workflow mints an installation token with **Contents: Write**,
  **Pull requests: Write** and **Actions: Write**. The mint step only succeeds when the
  installation grants those permissions, so the preflight passes the minted token to
  release-please without a second repository permission check.
- **Fine-grained token fallback:** set `RELEASE_PLEASE_TOKEN` with **Contents: Read and write**,
  **Pull requests: Read and write**, and **Actions: Read and write** if the token itself dispatches
  CD. Classic token: the `repo` scope, plus `workflow` for `workflow_dispatch`.

The token must belong to a dedicated bot identity - a GitHub App installation token or a machine
user - not the maintainer's personal PAT. GitHub never counts a self-approval, so a release PR
authored by the single maintainer could not satisfy the required review.

A regular `GITHUB_TOKEN` cannot be used for the release PR: pull requests created with it do not
trigger CI, so the release PR would never receive the required checks and could never be merged.
The preflight fails with an actionable message when neither credential is configured or the token
cannot push to the repository, so the bot never fails halfway through a release. Tags created with
a bot token do trigger tag-push workflows, but the release job dispatches CD explicitly so the
released tag is deployed even when a repository restricts token-triggered workflows.

## Normal release

```sh
# Preview the next version and the generated changelog without changing anything
pnpm release:dry-run

# Or through the workflow (Actions -> Release -> Run workflow), which is dry-run only
node scripts/release/dry-run.mjs --dry-run --from v0.1.0 --to HEAD
```

Then:

1. Review and merge the release PR once the full CI is green. Branch protection requires all
   checks, so a red release PR cannot be merged and no tag is created.
2. Confirm the tag and release were created exactly once. Re-running the workflow on the same
   commit is idempotent: release-please finds the existing tag, `release_created` stays false and
   CD is not dispatched again. The `release-please` concurrency group serializes runs.
3. Confirm the CD job built and pushed the released tag.

## Emergency release

Use this only when a critical fix cannot wait for the normal flow. Never create a tag manually
on unverified code and never bypass branch protection.

1. Open an incident ticket with the reason, the owner and an expiry.
2. Merge the fix through a normal pull request with all required checks green.
3. Let release-please update the release PR. If the bot could not open it (token or API
   incidents), run it locally with the same config and manifest:

   ```sh
   npx release-please@17 release-pr \
     --repo-url "https://github.com/${GITHUB_REPOSITORY}" \
     --token "$RELEASE_PLEASE_TOKEN" \
     --target-branch main \
     --config-file release-please-config.json \
     --manifest-file .release-please-manifest.json
   ```

4. Review and merge the release PR. The tag is still created only after the subsequent green CI
   run on `main`, because that is what triggers the release workflow.
5. If the release workflow itself did not run, re-run the last `Release` workflow run for that
   green CI run (`gh run rerun <run-id>`); the same event payload and commit SHA are reused. Do
   not create the tag manually, do not force-push `main` and do not move a published tag.

## SBOM recovery for an existing tag

If the release job created the tag but failed before attaching the SBOMs (for example the v1.1.0
Maven working-directory defect), do **not** delete or move the tag. Regenerate and attach the assets
from the existing tag with the recovery job:

```sh
gh workflow run release.yml --ref main -f recover_tag=v1.1.0
```

The `Release SBOM Recovery` job checks out the tag, builds the backend/frontend/AI-layer SBOMs,
validates and merges the inventory, signs it with cosign keyless, and uploads
`*.cdx.json` plus `*.bundle` with `--clobber`. It never runs release-please and never dispatches CD.
The normal release path now invokes Maven with `-f backend/pom.xml`, and the release-workflow test
suite rejects a regression to the root-directory invocation.

## Rollback of a bad tag

Prefer a forward fix. A published tag must never be moved, and `main` must never be force-pushed.

1. Revert the offending commit on `main` with a Conventional Commit (`revert: ...`). Release-please
   will produce the next patch release.
2. Point the deployment back at the previous good image while the patch release is prepared:

   ```sh
   argocd app rollback awap <previous-revision>
   # or keep the previous image digest in the deployment manifest
   ```

3. If the tag itself was created on the wrong commit (for example a manual mistake), delete the
   GitHub release and its tag, then let release-please recreate them from the corrected history:

   ```sh
   gh release delete vX.Y.Z --yes --cleanup-tag
   ```

4. Do not edit `CHANGELOG.md` or the manifest by hand to repair a bad release. If a repair is
   unavoidable, open a pull request that changes the **manifest** and let release-please
   regenerate the changelog; the changelog guard will reject a manual changelog edit.
5. Record the incident, the previous digest and the recovery tag in the ticket.

## Troubleshooting

| Symptom | Action |
|---|---|
| `Release Checks` fails with a commitlint error | Amend or rebase the offending commit; the log names the SHA and subject. |
| `Release Checks` fails with `CHANGELOG.md is generated exclusively by release-please` | Revert the manual changelog edit. |
| `Release` fails in `Resolve and verify the release token` | Configure `RELEASE_PLEASE_APP_ID` + `RELEASE_PLEASE_PRIVATE_KEY` (preferred) or grant `RELEASE_PLEASE_TOKEN` the permissions listed above. |
| `Release` says `main advanced` | The next green CI run releases the newer main commit; nothing to do. |
| Release PR is not opened | Check the `Release` workflow run for the green `CI` run of the merge commit and the token preflight. |
| Tag exists but CD did not run | Run the `CD` workflow with the tag, or re-run the release job; `release_created` guards duplicate dispatches. |
