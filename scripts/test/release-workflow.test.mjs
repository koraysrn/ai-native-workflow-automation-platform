import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const RELEASE_WORKFLOW = new URL("../../.github/workflows/release.yml", import.meta.url);
const CD_WORKFLOW = new URL("../../.github/workflows/cd.yml", import.meta.url);
const CI_WORKFLOW = new URL("../../.github/workflows/ci.yml", import.meta.url);
const MANIFEST = new URL("../../.release-please-manifest.json", import.meta.url);
const CONFIG = new URL("../../release-please-config.json", import.meta.url);
const PACKAGE = new URL("../../package.json", import.meta.url);
const POLICY_SCRIPT = new URL("../apply-branch-protection.sh", import.meta.url);
const POLICY_SNAPSHOT = new URL("../../settings/branch-protection.json", import.meta.url);
const GOVERNANCE = new URL("../../docs/repository-governance.md", import.meta.url);
const RUNBOOK = new URL("../../docs/operations-runbook.md", import.meta.url);
const RELEASE_RUNBOOK = new URL("../../docs/release-runbook.md", import.meta.url);
const COMMITLINT = new URL("../../node_modules/.bin/commitlint", import.meta.url);
const COMMITLINT_CONFIG = new URL("../../commitlint.config.cjs", import.meta.url);

test("the manifest and the release-please config describe the monorepo", async () => {
  const manifest = JSON.parse(await readFile(MANIFEST, "utf8"));
  const config = JSON.parse(await readFile(CONFIG, "utf8"));
  const packageJson = JSON.parse(await readFile(PACKAGE, "utf8"));

  assert.equal(manifest["."], packageJson.version);
  assert.equal(config.packages["."]["release-type"], "node");
  assert.equal(config.packages["."]["changelog-path"], "CHANGELOG.md");
  assert.equal(config.packages["."]["include-component-in-tag"], false);
  assert.match(config.packages["."]["bootstrap-sha"], /^[0-9a-f]{40}$/);
  assert.ok(config["changelog-sections"].some((section) => section.type === "feat"));
});

test("the release workflow uses the reviewable release PR flow", async () => {
  const workflow = await readFile(RELEASE_WORKFLOW, "utf8");
  assert.doesNotMatch(workflow, /skip-github-pull-request/);
  assert.match(workflow, /googleapis\/release-please-action@v5/);
  assert.match(workflow, /config-file: release-please-config\.json/);
  assert.match(workflow, /manifest-file: \.release-please-manifest\.json/);
});

test("the release workflow listens to the actual CI workflow name", async () => {
  const ci = await readFile(CI_WORKFLOW, "utf8");
  const release = await readFile(RELEASE_WORKFLOW, "utf8");
  const ciName = /^name: (.+)$/m.exec(ci)?.[1];
  assert.ok(ciName, "ci.yml has no workflow name");
  assert.match(release, new RegExp(`workflows: \\["${ciName}"\\]`));
});

test("a tag is only created after a green full CI run on main", async () => {
  const workflow = await readFile(RELEASE_WORKFLOW, "utf8");
  assert.match(workflow, /workflow_run:/);
  assert.match(workflow, /workflows: \["CI"\]/);
  assert.match(workflow, /types: \[completed\]/);
  assert.match(workflow, /branches: \[main\]/);
  assert.match(workflow, /github\.event\.workflow_run\.conclusion == 'success'/);
  assert.match(workflow, /github\.event\.workflow_run\.event == 'push'/);
  assert.match(workflow, /node scripts\/release\/verify-ci-gate\.mjs/);
  assert.match(workflow, /--github-output "\$GITHUB_OUTPUT"/);
  assert.match(workflow, /steps\.gate\.outputs\.skip != 'true'/);
});

test("release runs are serialized and cannot create a second tag or CD dispatch", async () => {
  const workflow = await readFile(RELEASE_WORKFLOW, "utf8");
  assert.match(workflow, /concurrency:\n {2}group: release-please\n {2}cancel-in-progress: false/);
  assert.match(workflow, /if: steps\.release\.outputs\.release_created == 'true'/);
  assert.match(workflow, /gh workflow run cd\.yml/);
});

test("the release workflow verifies the bot token and offers a dry run", async () => {
  const workflow = await readFile(RELEASE_WORKFLOW, "utf8");
  assert.match(workflow, /node scripts\/release\/token-preflight\.mjs/);
  assert.match(workflow, /secrets\.RELEASE_PLEASE_TOKEN/);
  assert.match(workflow, /actions\/create-github-app-token@v2/);
  assert.match(workflow, /secrets\.RELEASE_PLEASE_APP_ID/);
  assert.match(workflow, /secrets\.RELEASE_PLEASE_PRIVATE_KEY/);
  assert.match(workflow, /steps\.app-credentials\.outputs\.configured == 'true'/);
  assert.match(workflow, /steps\.app-token\.outputs\.token \|\| secrets\.RELEASE_PLEASE_TOKEN/);
  assert.match(workflow, /RELEASE_TOKEN_SOURCE: .*'github-app' \|\| 'token'/);
  assert.match(workflow, /inputs\.from/);
  assert.match(workflow, /scripts\/release\/dry-run\.mjs --dry-run/);
  assert.match(
    workflow,
    /permissions:\n {6}contents: write\n {6}pull-requests: write\n {6}actions: write/,
  );
});

test("CI runs the release guard, commit lint and dry run", async () => {
  const workflow = await readFile(CI_WORKFLOW, "utf8");
  assert.match(workflow, /^ {2}release:\n {4}name: Release Checks$/m);
  assert.match(workflow, /node --test "scripts\/test\/release-\*\.test\.mjs"/);
  assert.match(workflow, /node scripts\/release\/lint-commits\.mjs/);
  assert.match(workflow, /--message "\$PR_TITLE"/);
  assert.match(workflow, /node scripts\/release\/changelog-guard\.mjs/);
  assert.match(workflow, /scripts\/release\/dry-run\.mjs --dry-run/);
  assert.match(workflow, /pull-requests: read/);
});

test("CD can be dispatched with the released tag", async () => {
  const workflow = await readFile(CD_WORKFLOW, "utf8");
  const config = JSON.parse(await readFile(CONFIG, "utf8"));
  assert.equal(config.packages["."]["include-component-in-tag"], false);
  assert.match(workflow, /tags: \["v\*"\]/);
  assert.match(workflow, /workflow_dispatch:/);
  assert.match(workflow, /tag:/);
  assert.match(workflow, /tag-suffix: \$\{\{ inputs\.tag \|\| github\.ref_name \}\}/);
});

test("the branch protection policy requires the release checks", async () => {
  const script = await readFile(POLICY_SCRIPT, "utf8");
  const snapshot = await readFile(POLICY_SNAPSHOT, "utf8");
  const governance = await readFile(GOVERNANCE, "utf8");
  assert.match(script, /"Release Checks"/);
  assert.match(snapshot, /"Release Checks"/);
  assert.match(governance, /- `Release Checks`/);
  assert.match(governance, /CHANGELOG\.md` is bot-owned/);
});

test("the emergency release and tag rollback are documented", async () => {
  const runbook = await readFile(RUNBOOK, "utf8");
  const releaseRunbook = await readFile(RELEASE_RUNBOOK, "utf8");
  assert.match(runbook, /^## O23 — Release automation, emergency release and tag rollback \(issue #179\)$/m);
  assert.match(runbook, /\*\*Emergency release\.\*\*/);
  assert.match(runbook, /\*\*Bad tag\.\*\*/);
  assert.match(runbook, /force-push/);
  assert.match(releaseRunbook, /## Emergency release/);
  assert.match(releaseRunbook, /## Rollback of a bad tag/);
  assert.match(releaseRunbook, /gh release delete vX\.Y\.Z --yes --cleanup-tag/);
  assert.match(releaseRunbook, /argocd app rollback/);
});

test("commitlint rejects a non-conventional commit", (t) => {
  const bin = fileURLToPath(COMMITLINT);
  if (!existsSync(bin)) {
    if (process.env.RELEASE_TESTS_REQUIRE_COMMITLINT === "1") {
      assert.fail("commitlint is missing; the root pnpm install must run before this suite.");
    }
    t.skip("commitlint is not installed locally; run pnpm install at the repository root.");
    return;
  }

  const valid = spawnSync(bin, ["--config", fileURLToPath(COMMITLINT_CONFIG)], {
    input: "feat(release): add automation\n",
    encoding: "utf8",
  });
  assert.equal(valid.status, 0, `${valid.stdout}${valid.stderr}`);

  const invalid = spawnSync(bin, ["--config", fileURLToPath(COMMITLINT_CONFIG)], {
    input: "not a conventional commit\n",
    encoding: "utf8",
  });
  assert.notEqual(invalid.status, 0, "commitlint accepted a non-conventional commit");
});
