import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const SCRIPT = fileURLToPath(new URL("../apply-branch-protection.sh", import.meta.url));
const POLICY = fileURLToPath(new URL("../../settings/branch-protection.json", import.meta.url));
const DRIFT_WORKFLOW = fileURLToPath(
  new URL("../../.github/workflows/branch-protection-drift.yml", import.meta.url),
);
const CI_WORKFLOW = fileURLToPath(new URL("../../.github/workflows/ci.yml", import.meta.url));
const RUNBOOK = fileURLToPath(new URL("../../docs/operations-runbook.md", import.meta.url));
const GOVERNANCE = fileURLToPath(new URL("../../docs/repository-governance.md", import.meta.url));
const BREAK_GLASS_JSON = fileURLToPath(
  new URL("../../settings/break-glass-log.json", import.meta.url),
);
const BREAK_GLASS_DOC = fileURLToPath(
  new URL("../../docs/break-glass-log.md", import.meta.url),
);

const BASE_ENV = {
  PATH: process.env.PATH ?? "/usr/local/bin:/usr/bin:/bin",
  HOME: process.env.HOME ?? tmpdir(),
};

function runScript(args, env = {}) {
  return spawnSync("bash", [SCRIPT, ...args], {
    encoding: "utf8",
    env: { ...BASE_ENV, ...env },
  });
}

const expectedPolicy = JSON.parse(runScript(["--expected"]).stdout);
const REQUIRED_CHECKS = expectedPolicy.required_status_checks.contexts;
const SHARED_TRACE_DIR = await mkdtemp(path.join(tmpdir(), "awap-branch-protection-trace-"));
const SHARED_TRACE = path.join(SHARED_TRACE_DIR, "coverage.log");
await writeFile(SHARED_TRACE, "");

const FAKE_GH = `#!/usr/bin/env bash
set -euo pipefail
: "\${FAKE_GH_GH_LOG:=/dev/null}"
: "\${FAKE_GH_PUT_LOG:=/dev/null}"
printf 'gh %s\\n' "$*" >> "$FAKE_GH_GH_LOG"

if [ "\${1:-}" = "auth" ] && [ "\${2:-}" = "status" ]; then
  if [ "\${FAKE_GH_AUTH:-ok}" = "ok" ]; then exit 0; else exit 1; fi
fi

if [ "\${1:-}" = "repo" ] && [ "\${2:-}" = "view" ]; then
  if [ "\${FAKE_GH_REPO_VIEW:-fail}" = "ok" ]; then
    printf 'owner/repo\\n'
    exit 0
  fi
  exit 1
fi

if [ "\${1:-}" != "api" ]; then
  printf 'fake gh: unsupported command %s\\n' "$*" >&2
  exit 2
fi
shift

include=0
if [ "\${1:-}" = "-i" ]; then
  include=1
  shift
fi

if [ "\${1:-}" = "-X" ] && [ "\${2:-}" = "PUT" ]; then
  cat > "\${FAKE_GH_PUT_PAYLOAD:-/dev/null}"
  printf 'PUT %s\\n' "$*" >> "\$FAKE_GH_PUT_LOG"
  if [ "\${FAKE_GH_PUT:-ok}" = "ok" ]; then
    exit 0
  fi
  printf 'gh: Validation Failed (HTTP 422)\\n' >&2
  exit 1
fi

endpoint="\${1:-}"
case "$endpoint" in
  repos/*/branches/*/protection)
    case "\${FAKE_GH_PROTECTION:-match}" in
      missing)
        printf 'gh: Not Found (HTTP 404)\\n' >&2
        exit 1
        ;;
      error)
        printf 'gh: Server Error (HTTP 500)\\n' >&2
        exit 1
        ;;
      *)
        cat "\${FAKE_GH_PROTECTION_FILE:?}"
        exit 0
        ;;
    esac
    ;;
  repos/*)
    if [ "$include" -eq 1 ] && [ "\${FAKE_GH_REPO_API:-ok}" != "ok" ]; then
      printf 'gh: Forbidden (HTTP 403)\\n' >&2
      exit 1
    fi
    if [ "$include" -eq 1 ]; then
      printf 'HTTP/2.0 200 OK\\r\\n'
      if [ "\${FAKE_GH_SCOPES:-__NONE__}" != "__NONE__" ]; then
        printf 'x-oauth-scopes: %s\\r\\n' "\$FAKE_GH_SCOPES"
      fi
      printf 'content-type: application/json\\r\\n\\r\\n'
    fi
    printf '{"permissions":{"admin":%s}}\\n' "\${FAKE_GH_ADMIN:-true}"
    exit 0
    ;;
esac

printf 'fake gh: unsupported api endpoint %s\\n' "$endpoint" >&2
exit 2
`;

const DOCKER_CHECK_PREFIX = "Docker Build / Build Images (";

function dockerMatrixEntries(names) {
  return names
    .filter((name) => name.startsWith(DOCKER_CHECK_PREFIX))
    .map((name) => {
      const values = name.slice(DOCKER_CHECK_PREFIX.length, -1).split(", ");
      return { name: values[0], dockerfile: values[1], context: values[2] };
    });
}

async function writeWorkflows(directory, names) {
  await mkdir(directory, { recursive: true });
  const simpleNames = names.filter((name) => !name.startsWith(DOCKER_CHECK_PREFIX));
  await writeFile(
    path.join(directory, "ci.yml"),
    [
      "name: CI",
      "on:",
      "  pull_request:",
      "jobs:",
      ...simpleNames.map(
        (name, index) => `  job-${index}:\n    name: ${name}\n    runs-on: ubuntu-latest`,
      ),
      "  docker-build:",
      "    name: Docker Build",
      "    uses: ./.github/workflows/docker-build.yml",
      "",
    ].join("\n"),
  );
  await writeFile(
    path.join(directory, "docker-build.yml"),
    [
      "name: Docker Build",
      "on:",
      "  workflow_call:",
      "jobs:",
      "  build:",
      "    name: Build Images",
      "    strategy:",
      "      matrix:",
      "        include:",
      ...dockerMatrixEntries(names).flatMap((entry) => [
        `          - name: ${entry.name}`,
        `            dockerfile: ${entry.dockerfile}`,
        `            context: ${entry.context}`,
      ]),
      "",
    ].join("\n"),
  );
  await writeFile(
    path.join(directory, "version-compatibility.yml"),
    "name: Version Compatibility\njobs:\n  versions:\n    name: Verify Versions\n    runs-on: ubuntu-latest\n",
  );
}

function liveShape(policy) {
  return {
    required_status_checks: policy.required_status_checks,
    enforce_admins: { enabled: policy.enforce_admins },
    required_pull_request_reviews: policy.required_pull_request_reviews,
    required_conversation_resolution: { enabled: policy.required_conversation_resolution },
    allow_force_pushes: { enabled: policy.allow_force_pushes },
    allow_deletions: { enabled: policy.allow_deletions },
  };
}

async function createHarness() {
  const directory = await mkdtemp(path.join(tmpdir(), "awap-branch-protection-"));
  const fakeGh = path.join(directory, "gh");
  const workflows = path.join(directory, "workflows");
  const protectionFile = path.join(directory, "protection.json");
  const putPayload = path.join(directory, "put-payload.json");
  const putLog = path.join(directory, "put.log");
  const ghLog = path.join(directory, "gh.log");

  await writeFile(fakeGh, FAKE_GH);
  await chmod(fakeGh, 0o755);
  await writeWorkflows(workflows, [...REQUIRED_CHECKS, "Contract Verification"]);
  await writeFile(putLog, "");
  await writeFile(ghLog, "");
  await writeFile(protectionFile, JSON.stringify(liveShape(expectedPolicy)));

  return { directory, fakeGh, workflows, protectionFile, putPayload, putLog, ghLog };
}

function scenarioEnv(harness, overrides = {}) {
  return {
    GH_CLI: harness.fakeGh,
    BRANCH_PROTECTION_WORKFLOWS_DIR: harness.workflows,
    BRANCH_PROTECTION_TRACE_FILE: SHARED_TRACE,
    FAKE_GH_GH_LOG: harness.ghLog,
    FAKE_GH_PUT_LOG: harness.putLog,
    FAKE_GH_PUT_PAYLOAD: harness.putPayload,
    FAKE_GH_PROTECTION_FILE: harness.protectionFile,
    ...overrides,
  };
}

function runScenario(harness, args, overrides = {}) {
  const hasRepo = args.some((argument) => argument === "--repo" || argument.startsWith("--repo="));
  const fullArgs = hasRepo ? args : [...args, "--repo", "owner/repo"];
  return runScript(fullArgs, scenarioEnv(harness, overrides));
}

async function writeProtection(harness, value) {
  await writeFile(harness.protectionFile, JSON.stringify(value));
}

function driftedPolicy() {
  const drift = structuredClone(expectedPolicy);
  drift.required_status_checks.strict = false;
  drift.required_status_checks.contexts = drift.required_status_checks.contexts.filter(
    (check) => check !== "Branch Protection",
  );
  drift.enforce_admins = false;
  drift.required_pull_request_reviews.dismiss_stale_reviews = false;
  drift.required_pull_request_reviews.require_code_owner_reviews = true;
  drift.required_pull_request_reviews.required_approving_review_count = 0;
  drift.required_conversation_resolution = false;
  drift.allow_force_pushes = true;
  drift.allow_deletions = true;
  return drift;
}

test("help documents every mode and exits zero", () => {
  const result = runScript(["--help"], { BRANCH_PROTECTION_TRACE_FILE: SHARED_TRACE });
  assert.equal(result.status, 0);
  for (const mode of ["--dry-run", "--apply", "--verify", "--expected", "--list-paths"]) {
    assert.ok(result.stdout.includes(mode), `help output does not mention ${mode}`);
  }
});

test("the committed policy snapshot matches --expected", async () => {
  const committed = JSON.parse(await readFile(POLICY, "utf8"));
  const result = runScript(["--expected"], { BRANCH_PROTECTION_TRACE_FILE: SHARED_TRACE });
  assert.equal(result.status, 0);
  assert.deepEqual(committed, JSON.parse(result.stdout));
  assert.deepEqual(REQUIRED_CHECKS, [
    "Backend",
    "Local Stack",
    "Frontend",
    "AI Layer",
    "Verify Versions",
    "Vulnerability Scan",
    "Secret Scan",
    "Dependency Scan",
    "SBOM",
    "Code Quality",
    "Docker Build / Build Images (awap-backend, backend/Dockerfile, .)",
    "Docker Build / Build Images (awap-frontend, frontend/Dockerfile, .)",
    "Docker Build / Build Images (awap-ai-layer, ai-layer/Dockerfile, .)",
    "End-to-End Tests",
    "API Lint",
    "Observability Checks",
    "Observability Proof",
    "CDC Checks",
    "Load Tests",
    "Kubernetes Overlays",
    "Branch Protection",
    "Release Checks",
  ]);
});

test("the default mode is a non-mutating dry run", async () => {
  const harness = await createHarness();
  const result = runScenario(harness, []);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /DRY RUN: no GitHub API mutation/);
  assert.equal(await readFile(harness.putLog, "utf8"), "");
});

test("the repository can be resolved from gh repo view", async () => {
  const harness = await createHarness();
  const result = runScript(["--dry-run"], scenarioEnv(harness, { FAKE_GH_REPO_VIEW: "ok" }));
  assert.equal(result.status, 0);
  assert.match(result.stdout, /repos\/owner\/repo\/branches\/main\/protection/);
});

test("--branch and the --opt=value forms are parsed", async () => {
  const harness = await createHarness();
  const spaced = runScenario(harness, ["--dry-run", "--branch", "release"]);
  const equals = runScenario(harness, ["--dry-run", "--branch=release"]);
  const repoEquals = runScript(
    ["--dry-run", "--repo=owner/repo", `--workflows-dir=${harness.workflows}`],
    scenarioEnv(harness),
  );
  assert.equal(spaced.status, 0);
  assert.match(spaced.stdout, /repos\/owner\/repo\/branches\/release\/protection/);
  assert.equal(equals.status, 0);
  assert.match(equals.stdout, /repos\/owner\/repo\/branches\/release\/protection/);
  assert.equal(repoEquals.status, 0);
  assert.match(repoEquals.stdout, /DRY RUN/);
});

test("dry run prints the planned calls and performs no GitHub call", async () => {
  const harness = await createHarness();
  const result = runScenario(harness, ["--dry-run"]);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /DRY RUN: no GitHub API mutation/);
  assert.match(result.stdout, /GET repos\/owner\/repo\/branches\/main\/protection/);
  assert.match(result.stdout, /PUT repos\/owner\/repo\/branches\/main\/protection/);
  assert.match(result.stdout, /"strict": true/);
  assert.equal(await readFile(harness.putLog, "utf8"), "");
  assert.equal(await readFile(harness.ghLog, "utf8"), "");
});

test("apply creates protection when it is missing", async () => {
  const harness = await createHarness();
  const result = runScenario(harness, ["--apply"], { FAKE_GH_PROTECTION: "missing" });
  assert.equal(result.status, 0);
  assert.match(result.stdout, /created/);
  assert.match(await readFile(harness.putLog, "utf8"), /PUT .*branches\/main\/protection/);
  assert.deepEqual(JSON.parse(await readFile(harness.putPayload, "utf8")), expectedPolicy);
});

test("apply updates existing protection and converges on a retry", async () => {
  const harness = await createHarness();
  const first = runScenario(harness, ["--apply"]);
  const second = runScenario(harness, ["--apply"]);
  assert.equal(first.status, 0);
  assert.equal(second.status, 0);
  assert.match(first.stdout, /updated/);
  assert.match(second.stdout, /updated/);
  assert.equal((await readFile(harness.putLog, "utf8")).trim().split("\n").length, 2);
});

test("verify passes when the live policy equals the desired policy", async () => {
  const harness = await createHarness();
  const result = runScenario(harness, ["--verify"]);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /matches the reviewed policy/);
});

test("verify fails with every differing field name", async () => {
  const harness = await createHarness();
  await writeProtection(harness, liveShape(driftedPolicy()));
  const result = runScenario(harness, ["--verify"]);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /^DRIFT required_status_checks\.strict: expected true, got false$/m);
  assert.match(result.stderr, /^DRIFT required_status_checks\.contexts:/m);
  assert.match(result.stderr, /^DRIFT enforce_admins: expected true, got false$/m);
  assert.match(result.stderr, /required_approving_review_count: expected 1, got 0/);
  assert.match(result.stderr, /allow_deletions: expected false, got true/);
  assert.match(result.stderr, /--apply to converge/);
  assert.doesNotMatch(result.stderr, /^"DRIFT/m);
});

test("verify fails when main is unprotected", async () => {
  const harness = await createHarness();
  const result = runScenario(harness, ["--verify"], { FAKE_GH_PROTECTION: "missing" });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /DRIFT main: branch protection is not configured/);
});

test("usage errors are rejected before any GitHub call", async () => {
  const harness = await createHarness();
  const unknown = runScenario(harness, ["--bogus"]);
  const unexpected = runScenario(harness, ["positional"]);
  const conflicting = runScenario(harness, ["--apply", "--verify"]);
  const missing = runScript(["--repo"], scenarioEnv(harness));

  assert.equal(unknown.status, 1);
  assert.match(unknown.stderr, /unknown option '--bogus'/);
  assert.equal(unexpected.status, 1);
  assert.match(unexpected.stderr, /unexpected argument 'positional'/);
  assert.equal(conflicting.status, 1);
  assert.match(conflicting.stderr, /only one of/);
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /--repo requires an owner\/repo value/);
  assert.equal(await readFile(harness.ghLog, "utf8"), "");
});

test("missing gh and jq are reported clearly", async () => {
  const harness = await createHarness();
  const noGh = runScenario(harness, ["--dry-run"], { GH_CLI: "/definitely/missing/gh" });
  const noJq = runScript(["--expected"], {
    ...BASE_ENV,
    JQ_CLI: "/definitely/missing/jq",
    BRANCH_PROTECTION_TRACE_FILE: SHARED_TRACE,
  });

  assert.equal(noGh.status, 1);
  assert.match(noGh.stderr, /GitHub CLI \(gh\) is required/);
  assert.equal(noJq.status, 1);
  assert.match(noJq.stderr, /jq is required/);
});

test("repository resolution and policy validation fail loudly", async () => {
  const harness = await createHarness();
  const unresolved = runScript(
    ["--dry-run"],
    scenarioEnv(harness, { GH_REPO: "", GITHUB_REPOSITORY: "" }),
  );
  const invalid = runScenario(harness, ["--dry-run", "--repo", "not-a-repo"]);
  const emptyWorkflows = path.join(harness.directory, "empty-workflows");
  await mkdir(emptyWorkflows);
  const noWorkflows = runScenario(harness, ["--dry-run", "--workflows-dir", emptyWorkflows]);

  assert.equal(unresolved.status, 1);
  assert.match(unresolved.stderr, /cannot determine the repository/);
  assert.equal(invalid.status, 1);
  assert.match(invalid.stderr, /invalid repository 'not-a-repo'/);
  assert.equal(noWorkflows.status, 1);
  assert.match(noWorkflows.stderr, /no CI workflows found/);
});

test("an unknown required check and an unlisted workflow job are rejected", async () => {
  const harness = await createHarness();
  const unknownDir = path.join(harness.directory, "unknown-workflows");
  await writeWorkflows(
    unknownDir,
    [...REQUIRED_CHECKS.filter((check) => check !== "Branch Protection"), "Contract Verification"],
  );
  const unknown = runScenario(harness, ["--dry-run", "--workflows-dir", unknownDir]);

  const unlistedDir = path.join(harness.directory, "unlisted-workflows");
  await writeWorkflows(unlistedDir, [...REQUIRED_CHECKS, "Contract Verification", "Rogue Job"]);
  const unlisted = runScenario(harness, ["--dry-run", "--workflows-dir", unlistedDir]);

  assert.equal(unknown.status, 1);
  assert.match(
    unknown.stderr,
    /required check 'Branch Protection' does not match any workflow job or matrix context/,
  );
  assert.equal(unlisted.status, 1);
  assert.match(unlisted.stderr, /workflow job 'Rogue Job' is neither required/);
});

test("an extra Docker Build matrix entry must be added to REQUIRED_CHECKS", async () => {
  const harness = await createHarness();
  const workflow = path.join(harness.workflows, "docker-build.yml");
  const content = await readFile(workflow, "utf8");
  await writeFile(
    workflow,
    content.replace(
      "          - name: awap-backend",
      [
        "          - name: awap-worker",
        "            dockerfile: backend/Dockerfile",
        "            context: .",
        "          - name: awap-backend",
      ].join("\n"),
    ),
  );
  const result = runScenario(harness, ["--dry-run"]);
  assert.equal(result.status, 1);
  assert.match(
    result.stderr,
    /reusable workflow context 'Docker Build \/ Build Images \(awap-worker, backend\/Dockerfile, \.\)' is not required/,
  );
});

test("a Docker Build matrix change is detected as check-name drift", async () => {
  const harness = await createHarness();
  const workflow = path.join(harness.workflows, "docker-build.yml");
  const content = await readFile(workflow, "utf8");
  await writeFile(workflow, content.replace("awap-backend", "awap-backend-v2"));
  const result = runScenario(harness, ["--dry-run"]);
  assert.equal(result.status, 1);
  assert.match(
    result.stderr,
    /required check 'Docker Build \/ Build Images \(awap-backend, backend\/Dockerfile, \.\)' does not match/,
  );
});

test("remote preflight rejects bad auth, scope and admin permission before mutation", async () => {
  const harness = await createHarness();
  const unauthenticated = runScenario(harness, ["--apply"], { FAKE_GH_AUTH: "fail" });
  const unreachable = runScenario(harness, ["--apply"], { FAKE_GH_REPO_API: "fail" });
  const missingScope = runScenario(harness, ["--apply"], {
    FAKE_GH_SCOPES: "gist, read:org",
    FAKE_GH_ADMIN: "true",
  });
  const noAdmin = runScenario(harness, ["--apply"], {
    FAKE_GH_SCOPES: "repo",
    FAKE_GH_ADMIN: "false",
  });

  assert.equal(unauthenticated.status, 1);
  assert.match(unauthenticated.stderr, /gh is not authenticated/);
  assert.equal(unreachable.status, 1);
  assert.match(unreachable.stderr, /cannot read repos\/owner\/repo/);
  assert.equal(missingScope.status, 1);
  assert.match(missingScope.stderr, /token scopes \(gist, read:org\) lack 'repo'/);
  assert.equal(noAdmin.status, 1);
  assert.match(noAdmin.stderr, /does not have admin permission/);
  assert.equal(await readFile(harness.putLog, "utf8"), "");
});

test("apply and verify report API read and write failures", async () => {
  const harness = await createHarness();
  const readFailure = runScenario(harness, ["--verify"], { FAKE_GH_PROTECTION: "error" });
  const writeFailure = runScenario(harness, ["--apply"], {
    FAKE_GH_PROTECTION: "missing",
    FAKE_GH_PUT: "fail",
  });

  assert.equal(readFailure.status, 1);
  assert.match(readFailure.stderr, /cannot read branch protection for main in owner\/repo/);
  assert.equal(writeFailure.status, 1);
  assert.match(writeFailure.stderr, /failed to apply branch protection/);
});

test("the drift workflow reads live settings on a schedule with an admin token", async () => {
  const workflow = await readFile(DRIFT_WORKFLOW, "utf8");
  assert.match(workflow, /^name: Branch Protection Drift$/m);
  assert.match(workflow, /schedule:\n {4}- cron: "0 6 \* \* \*"/);
  assert.match(workflow, /workflow_dispatch:/);
  assert.match(workflow, /BRANCH_PROTECTION_TOKEN/);
  assert.match(workflow, /scripts\/apply-branch-protection\.sh --verify --repo "\$GITHUB_REPOSITORY"/);
  assert.match(workflow, /## Branch protection drift/);
  assert.match(workflow, /scripts\/apply-branch-protection\.sh --apply/);
});

test("CI runs bash -n, shellcheck, the coverage suite and a dry run", async () => {
  const workflow = await readFile(CI_WORKFLOW, "utf8");
  assert.match(workflow, /^ {2}branch-protection:\n {4}name: Branch Protection$/m);
  assert.match(workflow, /bash -n scripts\/apply-branch-protection\.sh/);
  assert.match(workflow, /shellcheck scripts\/apply-branch-protection\.sh/);
  assert.match(workflow, /node --test "scripts\/test\/apply-branch-protection\.test\.mjs"/);
  assert.match(workflow, /scripts\/apply-branch-protection\.sh --dry-run --repo "\$GITHUB_REPOSITORY"/);
});

test("break-glass log fully records incident #257 and its restores", async () => {
  const log = JSON.parse(await readFile(BREAK_GLASS_JSON, "utf8"));
  assert.equal(log.incident, 257);
  assert.equal(log.owner, "koraysrn");
  assert.equal(log.expiry, "2026-11-13T23:59:59Z");
  assert.equal(log.restore.approval_count_after, 1);
  assert.equal(log.restore.enforce_admins_after, true);
  assert.equal(log.post_incident.approval_count, 1);
  assert.equal(log.post_incident.enforce_admins, true);
  assert.match(log.post_incident.main_ci_sha, /^[0-9a-f]{40}$/);
  assert.equal(typeof log.post_incident.final_drift_success_run, "number");
  assert.match(log.scope, /only required_approving_review_count/i);

  // Issue #257 covers the sequential pipeline #197-#205 plus the CI fix PR #258.
  const expected = [
    [null, 258],
    [197, 256],
    [198, 260],
    [199, 261],
    [200, 262],
    [201, 263],
    [202, 264],
    [203, 265],
    [204, 266],
    [205, 267],
  ];
  assert.deepEqual(
    log.merges.map((merge) => [merge.issue, merge.pr]),
    expected,
  );
  for (const merge of log.merges) {
    assert.match(merge.merge_sha, /^[0-9a-f]{40}$/);
    assert.match(merge.merged_at, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
    assert.equal(merge.checks_green, true);
    assert.equal(merge.restore_apply, "updated");
    assert.equal(merge.restore_verify, "matches");
  }

  const doc = await readFile(BREAK_GLASS_DOC, "utf8");
  for (const merge of log.merges) {
    assert.match(doc, new RegExp(`#${merge.pr}`));
    assert.ok(doc.includes(merge.merge_sha), `missing ${merge.merge_sha} in break-glass doc`);
  }
  assert.match(doc, /Incident #257 is closed/);
  assert.match(doc, /cannot authorise any\s+further merge/);
  assert.match(doc, new RegExp(String(log.post_incident.final_drift_success_run)));
  assert.ok(doc.includes(log.post_incident.main_ci_sha));

  const runbook = await readFile(RUNBOOK, "utf8");
  assert.match(runbook, /break-glass-log\.md/);
  assert.match(runbook, /Append the merge/);
  const governance = await readFile(GOVERNANCE, "utf8");
  assert.match(governance, /break-glass-log\.md/);
});

test("governance docs document the single-maintainer exception and break-glass path", async () => {
  const governance = await readFile(GOVERNANCE, "utf8");
  const runbook = await readFile(RUNBOOK, "utf8");
  assert.match(governance, /scripts\/apply-branch-protection\.sh/);
  assert.match(governance, /branch-protection-drift\.yml/);
  assert.match(governance, /single-maintainer/i);
  assert.match(runbook, /^## O22 — Branch protection drift and break-glass \(issue #178\)$/m);
  assert.match(runbook, /scripts\/apply-branch-protection\.sh --verify/);
  assert.match(runbook, /incident ticket/i);
});

test("every declared script path and error path is exercised", async () => {
  const listed = runScript(["--list-paths"], {
    BRANCH_PROTECTION_TRACE_FILE: SHARED_TRACE,
  }).stdout.trim().split("\n");
  const executed = new Set(
    (await readFile(SHARED_TRACE, "utf8"))
      .trim()
      .split("\n")
      .filter(Boolean),
  );
  const missing = listed.filter((pathId) => !executed.has(pathId));
  const unknown = [...executed].filter((pathId) => !listed.includes(pathId));
  assert.deepEqual(missing, [], `unexercised script paths: ${missing.join(", ")}`);
  assert.deepEqual(unknown, [], `unknown traced paths: ${unknown.join(", ")}`);
});
