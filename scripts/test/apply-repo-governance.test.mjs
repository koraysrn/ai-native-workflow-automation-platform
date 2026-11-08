import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const SCRIPT = fileURLToPath(new URL("../apply-repo-governance.sh", import.meta.url));
const STATE = fileURLToPath(
  new URL("../../settings/repository-governance.json", import.meta.url),
);
const DRIFT_WORKFLOW = fileURLToPath(
  new URL("../../.github/workflows/repository-governance-drift.yml", import.meta.url),
);
const CI_WORKFLOW = fileURLToPath(new URL("../../.github/workflows/ci.yml", import.meta.url));
const GOVERNANCE = fileURLToPath(
  new URL("../../docs/repository-governance.md", import.meta.url),
);
const RUNBOOK = fileURLToPath(new URL("../../docs/operations-runbook.md", import.meta.url));

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

const desired = JSON.parse(await readFile(STATE, "utf8"));
const SHARED_TRACE_DIR = await mkdtemp(path.join(tmpdir(), "awap-repo-governance-trace-"));
const SHARED_TRACE = path.join(SHARED_TRACE_DIR, "coverage.log");
await writeFile(SHARED_TRACE, "");

const FAKE_GH = `#!/usr/bin/env bash
set -euo pipefail
: "\${FAKE_GH_LOG:=/dev/null}"
: "\${FAKE_GH_MUTATION_LOG:=/dev/null}"
printf 'gh %s\\n' "$*" >> "$FAKE_GH_LOG"

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
method="GET"
endpoint=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -i) include=1; shift ;;
    --paginate) shift ;;
    -X) method="\${2:?}"; shift 2 ;;
    --method) method="\${2:?}"; shift 2 ;;
    -f|-F) shift 2 ;;
    --input) cat >/dev/null; shift 2 ;;
    -*) printf 'fake gh: unsupported option %s\\n' "$1" >&2; exit 2 ;;
    *) endpoint="$1"; shift ;;
  esac
done

if [ "$method" != "GET" ]; then
  printf '%s %s\\n' "$method" "$endpoint" >> "$FAKE_GH_MUTATION_LOG"
fi

path="\${endpoint%%\\?*}"
case "$path" in
  repos/*/labels)
    if [ "$method" = "GET" ]; then
      if [ "\${FAKE_GH_LABELS_STATUS:-ok}" != "ok" ]; then
        printf 'gh: Server Error (HTTP 500)\\n' >&2
        exit 1
      fi
      cat "\${FAKE_GH_LABELS_FILE:?}"
      exit 0
    fi
    if [ "$method" = "POST" ]; then
      if [ "\${FAKE_GH_FAIL_LABEL_CREATE:-no}" = "yes" ]; then
        printf 'gh: Validation Failed (HTTP 422)\\n' >&2
        exit 1
      fi
      exit 0
    fi
    ;;
  repos/*/labels/*)
    if [ "$method" = "PATCH" ]; then
      if [ "\${FAKE_GH_FAIL_LABEL_UPDATE:-no}" = "yes" ]; then
        printf 'gh: Validation Failed (HTTP 422)\\n' >&2
        exit 1
      fi
      exit 0
    fi
    if [ "$method" = "DELETE" ]; then
      if [ "\${FAKE_GH_FAIL_LABEL_DELETE:-no}" = "yes" ]; then
        printf 'gh: Validation Failed (HTTP 422)\\n' >&2
        exit 1
      fi
      exit 0
    fi
    ;;
  repos/*/milestones)
    if [ "$method" = "GET" ]; then
      if [ "\${FAKE_GH_MILESTONES_STATUS:-ok}" != "ok" ]; then
        printf 'gh: Server Error (HTTP 500)\\n' >&2
        exit 1
      fi
      cat "\${FAKE_GH_MILESTONES_FILE:?}"
      exit 0
    fi
    if [ "$method" = "POST" ]; then
      if [ "\${FAKE_GH_FAIL_MILESTONE_CREATE:-no}" = "yes" ]; then
        printf 'gh: Validation Failed (HTTP 422)\\n' >&2
        exit 1
      fi
      exit 0
    fi
    ;;
  repos/*/milestones/*)
    if [ "$method" = "PATCH" ]; then
      if [ "\${FAKE_GH_FAIL_MILESTONE_UPDATE:-no}" = "yes" ]; then
        printf 'gh: Validation Failed (HTTP 422)\\n' >&2
        exit 1
      fi
      exit 0
    fi
    ;;
  repos/*)
    if [ "$include" -eq 1 ]; then
      if [ "\${FAKE_GH_REPO_STATUS:-ok}" != "ok" ]; then
        printf 'gh: Forbidden (HTTP 403)\\n' >&2
        exit 1
      fi
      printf 'HTTP/2.0 200 OK\\r\\n'
      if [ "\${FAKE_GH_SCOPES:-__NONE__}" != "__NONE__" ]; then
        printf 'x-oauth-scopes: %s\\r\\n' "$FAKE_GH_SCOPES"
      fi
      printf 'content-type: application/json\\r\\n\\r\\n'
      printf '{"permissions":{"admin":%s,"push":%s},"allow_squash_merge":%s,"allow_merge_commit":%s,"allow_rebase_merge":%s,"delete_branch_on_merge":%s}\\n' \\
        "\${FAKE_GH_ADMIN:-true}" "\${FAKE_GH_PUSH:-true}" \\
        "\${FAKE_GH_ALLOW_SQUASH_MERGE:-true}" "\${FAKE_GH_ALLOW_MERGE_COMMIT:-false}" \\
        "\${FAKE_GH_ALLOW_REBASE_MERGE:-false}" "\${FAKE_GH_DELETE_BRANCH_ON_MERGE:-true}"
      exit 0
    fi
    if [ "$method" = "PATCH" ]; then
      if [ "\${FAKE_GH_FAIL_SETTINGS:-no}" = "yes" ]; then
        printf 'gh: Validation Failed (HTTP 422)\\n' >&2
        exit 1
      fi
      exit 0
    fi
    ;;
esac

printf 'fake gh: unsupported api endpoint %s\\n' "$endpoint" >&2
exit 2
`;

const FAKE_BRANCH_PROTECTION = `#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >> "\${FAKE_GH_BP_LOG:?}"
if [ "\${FAKE_GH_BP_FAIL:-no}" = "yes" ]; then
  printf 'fake branch protection script failed\\n' >&2
  exit 1
fi
exit 0
`;

function liveLabels(labels) {
  return labels.map(({ name, description, color }) => ({
    name,
    description,
    color,
    url: `https://api.github.com/repos/owner/repo/labels/${encodeURIComponent(name)}`,
  }));
}

function liveMilestones(milestones) {
  return milestones.map(({ title, description, state }, index) => ({
    number: index + 1,
    title,
    description,
    state,
  }));
}

async function createHarness() {
  const directory = await mkdtemp(path.join(tmpdir(), "awap-repo-governance-"));
  const fakeGh = path.join(directory, "gh");
  const stateFile = path.join(directory, "repository-governance.json");
  const labelsFile = path.join(directory, "labels.json");
  const milestonesFile = path.join(directory, "milestones.json");
  const branchProtectionScript = path.join(directory, "apply-branch-protection.sh");
  const branchProtectionLog = path.join(directory, "branch-protection.log");
  const ghLog = path.join(directory, "gh.log");
  const mutationLog = path.join(directory, "mutations.log");

  await writeFile(fakeGh, FAKE_GH);
  await chmod(fakeGh, 0o755);
  await writeFile(stateFile, JSON.stringify(desired, null, 2));
  await writeFile(labelsFile, JSON.stringify(liveLabels(desired.labels), null, 2));
  await writeFile(milestonesFile, JSON.stringify(liveMilestones(desired.milestones), null, 2));
  await writeFile(branchProtectionScript, FAKE_BRANCH_PROTECTION);
  await chmod(branchProtectionScript, 0o755);
  await writeFile(branchProtectionLog, "");
  await writeFile(ghLog, "");
  await writeFile(mutationLog, "");

  return {
    directory,
    fakeGh,
    stateFile,
    labelsFile,
    milestonesFile,
    branchProtectionScript,
    branchProtectionLog,
    ghLog,
    mutationLog,
  };
}

function scenarioEnv(harness, overrides = {}) {
  return {
    GH_CLI: harness.fakeGh,
    REPO_GOVERNANCE_STATE_FILE: harness.stateFile,
    REPO_GOVERNANCE_TRACE_FILE: SHARED_TRACE,
    REPO_GOVERNANCE_BRANCH_PROTECTION_SCRIPT: harness.branchProtectionScript,
    FAKE_GH_LOG: harness.ghLog,
    FAKE_GH_MUTATION_LOG: harness.mutationLog,
    FAKE_GH_LABELS_FILE: harness.labelsFile,
    FAKE_GH_MILESTONES_FILE: harness.milestonesFile,
    FAKE_GH_BP_LOG: harness.branchProtectionLog,
    ...overrides,
  };
}

function runScenario(harness, args, overrides = {}) {
  const hasRepo = args.some((argument) => argument === "--repo" || argument.startsWith("--repo="));
  const fullArgs = hasRepo ? args : [...args, "--repo", "owner/repo"];
  return runScript(fullArgs, scenarioEnv(harness, overrides));
}

async function writeLabels(harness, labels) {
  await writeFile(harness.labelsFile, JSON.stringify(liveLabels(labels), null, 2));
}

async function writeMilestones(harness, milestones) {
  await writeFile(harness.milestonesFile, JSON.stringify(liveMilestones(milestones), null, 2));
}

async function writeState(harness, state) {
  await writeFile(harness.stateFile, JSON.stringify(state, null, 2));
}

function stateWith(mutator) {
  const copy = structuredClone(desired);
  mutator(copy);
  return copy;
}

async function readLog(file) {
  return readFile(file, "utf8");
}

test("help documents every mode and exits zero", () => {
  const result = runScript(["--help"], { REPO_GOVERNANCE_TRACE_FILE: SHARED_TRACE });
  assert.equal(result.status, 0);
  for (const mode of ["--dry-run", "--apply", "--verify", "--expected", "--list-paths", "--prune"]) {
    assert.ok(result.stdout.includes(mode), `help output does not mention ${mode}`);
  }
});

test("list-paths prints every trace id", () => {
  const result = runScript(["--list-paths"], { REPO_GOVERNANCE_TRACE_FILE: SHARED_TRACE });
  assert.equal(result.status, 0);
  assert.match(result.stdout, /mode_apply_pruned/);
  assert.match(result.stdout, /error_state_duplicate_milestone/);
});

test("--expected matches the committed desired state", () => {
  const result = runScript(["--expected"], { REPO_GOVERNANCE_TRACE_FILE: SHARED_TRACE });
  assert.equal(result.status, 0);
  assert.deepEqual(JSON.parse(result.stdout), desired);
  assert.equal(desired.labels.every((label) => label.owner.startsWith("@")), true);
  assert.equal(desired.labels.every((label) => /^[0-9a-fA-F]{6}$/.test(label.color)), true);
});

test("state validation fails with a specific error for every corrupt shape", async () => {
  const harness = await createHarness();
  const missing = path.join(harness.directory, "missing.json");
  const invalidJson = path.join(harness.directory, "invalid.json");
  await writeFile(invalidJson, "{");

  const cases = [
    {
      file: missing,
      message: /desired-state file .* does not exist/,
    },
    {
      file: invalidJson,
      message: /is not valid JSON/,
    },
    {
      state: stateWith((state) => {
        delete state.labels[0].color;
      }),
      message: /every label needs a name/,
    },
    {
      state: stateWith((state) => {
        state.labels.push(structuredClone(state.labels[0]));
      }),
      message: /duplicate label names/,
    },
    {
      state: stateWith((state) => {
        delete state.milestones[0].owner;
      }),
      message: /every milestone needs a title/,
    },
    {
      state: stateWith((state) => {
        state.milestones.push(structuredClone(state.milestones[0]));
      }),
      message: /duplicate milestone titles/,
    },
    {
      state: stateWith((state) => {
        state.merge_policy.allow_squash_merge = "yes";
      }),
      message: /merge_policy must define/,
    },
  ];

  for (const scenario of cases) {
    let stateFile = scenario.file;
    if (scenario.state) {
      stateFile = path.join(
        harness.directory,
        `state-${cases.indexOf(scenario)}.json`,
      );
      await writeFile(stateFile, JSON.stringify(scenario.state, null, 2));
    }
    const result = runScript(["--expected"], {
      REPO_GOVERNANCE_STATE_FILE: stateFile,
      REPO_GOVERNANCE_TRACE_FILE: SHARED_TRACE,
    });
    assert.equal(result.status, 1, `unexpected status for ${scenario.message}`);
    assert.match(result.stderr, scenario.message);
  }
});

test("usage errors are rejected before any GitHub call", async () => {
  const harness = await createHarness();
  const unknown = runScenario(harness, ["--bogus"]);
  const unexpected = runScenario(harness, ["positional"]);
  const conflicting = runScenario(harness, ["--apply", "--verify"]);
  const missing = runScript(["--repo"], scenarioEnv(harness));
  const pruneWithVerify = runScenario(harness, ["--verify", "--prune"]);

  assert.equal(unknown.status, 1);
  assert.match(unknown.stderr, /unknown option '--bogus'/);
  assert.equal(unexpected.status, 1);
  assert.match(unexpected.stderr, /unexpected argument 'positional'/);
  assert.equal(conflicting.status, 1);
  assert.match(conflicting.stderr, /only one of/);
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /--repo requires an owner\/repo value/);
  assert.equal(pruneWithVerify.status, 1);
  assert.match(pruneWithVerify.stderr, /--prune is only valid with --apply or --dry-run/);
  assert.equal(await readLog(harness.ghLog), "");
});

test("missing gh and jq are reported clearly", async () => {
  const harness = await createHarness();
  const noGh = runScenario(harness, ["--dry-run"], { GH_CLI: "/definitely/missing/gh" });
  const noJq = runScript(["--expected"], {
    ...BASE_ENV,
    JQ_CLI: "/definitely/missing/jq",
    REPO_GOVERNANCE_STATE_FILE: harness.stateFile,
    REPO_GOVERNANCE_TRACE_FILE: SHARED_TRACE,
  });

  assert.equal(noGh.status, 1);
  assert.match(noGh.stderr, /GitHub CLI \(gh\) is required/);
  assert.equal(noJq.status, 1);
  assert.match(noJq.stderr, /jq is required/);
});

test("repository resolution and validation fail loudly", async () => {
  const harness = await createHarness();
  const unresolved = runScript(["--dry-run"], {
    ...scenarioEnv(harness),
    GH_REPO: "",
    GITHUB_REPOSITORY: "",
    FAKE_GH_REPO_VIEW: "fail",
  });
  const invalid = runScenario(harness, ["--dry-run", "--repo", "not-a-repo"]);

  assert.equal(unresolved.status, 1);
  assert.match(unresolved.stderr, /cannot determine the repository/);
  assert.equal(invalid.status, 1);
  assert.match(invalid.stderr, /invalid repository 'not-a-repo'/);
});

test("the repository can be resolved from gh repo view", async () => {
  const harness = await createHarness();
  const result = runScript(["--dry-run"], {
    ...scenarioEnv(harness),
    GH_REPO: "",
    GITHUB_REPOSITORY: "",
    FAKE_GH_REPO_VIEW: "ok",
  });
  assert.equal(result.status, 0);
  assert.match(result.stdout, /Planned calls for owner\/repo/);
});

test("the default mode is a non-mutating dry run", async () => {
  const harness = await createHarness();
  const result = runScenario(harness, []);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /DRY RUN: no GitHub API mutation/);
  assert.match(result.stdout, /PATCH repos\/owner\/repo \(merge policy\)/);
  assert.match(result.stdout, /POST repos\/owner\/repo\/labels when missing: kind\/bug/);
  assert.match(result.stdout, /apply-branch-protection\.sh --apply --repo owner\/repo/);
  assert.equal(await readLog(harness.mutationLog), "");
  assert.equal(await readLog(harness.ghLog), "");
});

test("dry-run with --prune prints the planned deletes without calling GitHub", async () => {
  const harness = await createHarness();
  const result = runScenario(harness, ["--dry-run", "--prune"]);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /DELETE repos\/owner\/repo\/labels\/\{name\}/);
  assert.equal(await readLog(harness.mutationLog), "");
  assert.equal(await readLog(harness.ghLog), "");
});

test("verify succeeds and reports unknown labels and milestones without deleting them", async () => {
  const harness = await createHarness();
  await writeLabels(harness, [
    ...desired.labels,
    { name: "dependencies", description: "Dependency updates", color: "0366d6", owner: "@korux" },
  ]);
  await writeMilestones(harness, [
    ...desired.milestones,
    { title: "Ön Faz", description: "Legacy milestone", state: "open", owner: "@korux" },
  ]);

  const result = runScenario(harness, ["--verify"]);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /matches the reviewed labels, milestones and merge policy/);
  assert.match(result.stderr, /UNKNOWN label 'dependencies'/);
  assert.match(result.stderr, /UNKNOWN milestone 'Ön Faz'/);
  assert.equal(await readLog(harness.mutationLog), "");
});

test("verify fails on a missing label and reports the missing name", async () => {
  const harness = await createHarness();
  await writeLabels(
    harness,
    desired.labels.filter((label) => label.name !== "kind/bug"),
  );
  const result = runScenario(harness, ["--verify"]);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /DRIFT label 'kind\/bug' is missing/);
});

test("verify fails on drifted label color and description", async () => {
  const harness = await createHarness();
  await writeLabels(
    harness,
    desired.labels.map((label) =>
      label.name === "kind/bug"
        ? { ...label, color: "000000", description: "Wrong" }
        : label,
    ),
  );
  const result = runScenario(harness, ["--verify"]);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /DRIFT label 'kind\/bug' color: expected d73a4a, got 000000/);
  assert.match(result.stderr, /DRIFT label 'kind\/bug' description:/);
});

test("verify fails on a missing milestone", async () => {
  const harness = await createHarness();
  await writeMilestones(
    harness,
    desired.milestones.filter((milestone) => milestone.title !== "Preparation Phase"),
  );
  const result = runScenario(harness, ["--verify"]);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /DRIFT milestone 'Preparation Phase' is missing/);
});

test("verify fails on drifted milestone description and state", async () => {
  const harness = await createHarness();
  await writeMilestones(
    harness,
    desired.milestones.map((milestone) =>
      milestone.title === "Preparation Phase"
        ? { ...milestone, description: "Wrong", state: "closed" }
        : milestone,
    ),
  );
  const result = runScenario(harness, ["--verify"]);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /DRIFT milestone 'Preparation Phase' description:/);
  assert.match(result.stderr, /DRIFT milestone 'Preparation Phase' state: expected open, got closed/);
});

test("verify fails on duplicate milestone titles", async () => {
  const harness = await createHarness();
  await writeMilestones(harness, [
    ...desired.milestones,
    { ...desired.milestones[0], owner: "@koraysrn" },
  ]);
  const result = runScenario(harness, ["--verify"]);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /DRIFT milestone 'Preparation Phase' has 2 entries/);
});

test("verify fails on merge policy drift", async () => {
  const harness = await createHarness();
  const result = runScenario(harness, ["--verify"], {
    FAKE_GH_ALLOW_SQUASH_MERGE: "false",
  });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /DRIFT merge_policy\.allow_squash_merge: expected true, got false/);
});

test("verify works with a read-only token and reports API read failures", async () => {
  const harness = await createHarness();
  const readOnly = runScenario(harness, ["--verify"], {
    FAKE_GH_ADMIN: "false",
    FAKE_GH_PUSH: "false",
    FAKE_GH_SCOPES: "repo",
  });
  const labelsFailure = runScenario(harness, ["--verify"], {
    FAKE_GH_LABELS_STATUS: "error",
  });
  const milestonesFailure = runScenario(harness, ["--verify"], {
    FAKE_GH_MILESTONES_STATUS: "error",
  });

  assert.equal(readOnly.status, 0);
  assert.match(readOnly.stdout, /matches the reviewed labels/);
  assert.equal(labelsFailure.status, 1);
  assert.match(labelsFailure.stderr, /cannot read repos\/owner\/repo\/labels\?per_page=100/);
  assert.equal(milestonesFailure.status, 1);
  assert.match(
    milestonesFailure.stderr,
    /cannot read repos\/owner\/repo\/milestones\?state=all&per_page=100/,
  );
});

test("apply creates missing labels, milestones and merge settings", async () => {
  const harness = await createHarness();
  await writeLabels(harness, []);
  await writeMilestones(harness, []);
  const result = runScenario(harness, ["--apply"], {
    FAKE_GH_ALLOW_SQUASH_MERGE: "false",
  });

  assert.equal(result.status, 0);
  assert.match(result.stdout, /created label: kind\/bug/);
  assert.match(result.stdout, /created milestone: Preparation Phase/);
  assert.match(result.stdout, /updated merge policy/);
  const mutations = await readLog(harness.mutationLog);
  assert.match(mutations, /POST repos\/owner\/repo\/labels/);
  assert.match(mutations, /POST repos\/owner\/repo\/milestones/);
  assert.match(mutations, /PATCH repos\/owner\/repo$/m);
  const ghLog = await readLog(harness.ghLog);
  assert.match(ghLog, /name=kind\/bug/);
  assert.match(ghLog, /color=d73a4a/);
  assert.match(ghLog, /title=Preparation Phase/);
  assert.match(await readLog(harness.branchProtectionLog), /--apply --repo owner\/repo/);
});

test("apply updates drifted labels and milestones in place", async () => {
  const harness = await createHarness();
  await writeLabels(
    harness,
    desired.labels.map((label) =>
      label.name === "kind/bug"
        ? { ...label, color: "000000", description: "Wrong" }
        : label,
    ),
  );
  await writeMilestones(
    harness,
    desired.milestones.map((milestone) =>
      milestone.title === "Preparation Phase"
        ? { ...milestone, description: "Wrong", state: "closed" }
        : milestone,
    ),
  );
  const result = runScenario(harness, ["--apply"]);

  assert.equal(result.status, 0);
  assert.match(result.stdout, /updated label: kind\/bug/);
  assert.match(result.stdout, /updated milestone: Preparation Phase/);
  const mutations = await readLog(harness.mutationLog);
  assert.match(mutations, /PATCH repos\/owner\/repo\/labels\/kind%2Fbug/);
  assert.match(mutations, /PATCH repos\/owner\/repo\/milestones\/1/);
});

test("apply is idempotent when the live state already matches", async () => {
  const harness = await createHarness();
  const first = runScenario(harness, ["--apply"]);
  const second = runScenario(harness, ["--apply"]);

  assert.equal(first.status, 0);
  assert.equal(second.status, 0);
  assert.match(first.stdout, /0 created, 0 updated, 0 pruned/);
  assert.match(second.stdout, /0 created, 0 updated, 0 pruned/);
  assert.equal(await readLog(harness.mutationLog), "");
  assert.equal((await readLog(harness.branchProtectionLog)).trim().split("\n").length, 2);
});

test("apply never deletes an unknown label without --prune and prunes only with it", async () => {
  const harness = await createHarness();
  await writeLabels(harness, [
    ...desired.labels,
    { name: "legacy", description: "Old label", color: "ededed", owner: "@koraysrn" },
  ]);

  const withoutPrune = runScenario(harness, ["--apply"]);
  assert.equal(withoutPrune.status, 0);
  assert.doesNotMatch(await readLog(harness.mutationLog), /DELETE/);

  await writeFile(harness.mutationLog, "");
  const withPrune = runScenario(harness, ["--apply", "--prune"]);
  assert.equal(withPrune.status, 0);
  assert.match(withPrune.stdout, /pruned label: legacy/);
  const mutations = await readLog(harness.mutationLog);
  assert.match(mutations, /DELETE repos\/owner\/repo\/labels\/legacy/);
  assert.doesNotMatch(mutations, /DELETE repos\/owner\/repo\/labels\/kind%2Fbug/);
});

test("apply reconciles a duplicate milestone by updating the first entry", async () => {
  const harness = await createHarness();
  await writeMilestones(harness, [
    { ...desired.milestones[0], description: "Wrong" },
    ...desired.milestones.slice(1),
    { ...desired.milestones[0] },
  ]);
  const result = runScenario(harness, ["--apply"]);

  assert.equal(result.status, 0);
  assert.match(result.stderr, /WARNING duplicate milestone 'Preparation Phase'/);
  const mutations = await readLog(harness.mutationLog);
  assert.match(mutations, /PATCH repos\/owner\/repo\/milestones\/1/);
  assert.doesNotMatch(mutations, /POST repos\/owner\/repo\/milestones/);
});

test("apply reports label, milestone, settings, prune and branch protection failures", async () => {
  const labelCreate = await createHarness();
  await writeLabels(labelCreate, []);
  assert.equal(
    runScenario(labelCreate, ["--apply"], { FAKE_GH_FAIL_LABEL_CREATE: "yes" }).status,
    1,
  );

  const labelUpdate = await createHarness();
  await writeLabels(
    labelUpdate,
    desired.labels.map((label) =>
      label.name === "kind/bug" ? { ...label, color: "000000" } : label,
    ),
  );
  assert.equal(
    runScenario(labelUpdate, ["--apply"], { FAKE_GH_FAIL_LABEL_UPDATE: "yes" }).status,
    1,
  );

  const milestoneCreate = await createHarness();
  await writeMilestones(milestoneCreate, []);
  assert.equal(
    runScenario(milestoneCreate, ["--apply"], { FAKE_GH_FAIL_MILESTONE_CREATE: "yes" })
      .status,
    1,
  );

  const milestoneUpdate = await createHarness();
  await writeMilestones(
    milestoneUpdate,
    desired.milestones.map((milestone) =>
      milestone.title === "Preparation Phase"
        ? { ...milestone, description: "Wrong" }
        : milestone,
    ),
  );
  assert.equal(
    runScenario(milestoneUpdate, ["--apply"], { FAKE_GH_FAIL_MILESTONE_UPDATE: "yes" })
      .status,
    1,
  );

  const settings = await createHarness();
  assert.equal(
    runScenario(settings, ["--apply"], {
      FAKE_GH_ALLOW_SQUASH_MERGE: "false",
      FAKE_GH_FAIL_SETTINGS: "yes",
    }).status,
    1,
  );

  const prune = await createHarness();
  await writeLabels(prune, [
    ...desired.labels,
    { name: "legacy", description: "Old label", color: "ededed", owner: "@koraysrn" },
  ]);
  assert.equal(
    runScenario(prune, ["--apply", "--prune"], { FAKE_GH_FAIL_LABEL_DELETE: "yes" }).status,
    1,
  );

  const branchProtection = await createHarness();
  assert.equal(
    runScenario(branchProtection, ["--apply"], { FAKE_GH_BP_FAIL: "yes" }).status,
    1,
  );

  const missingBranchProtection = await createHarness();
  assert.equal(
    runScenario(missingBranchProtection, ["--apply"], {
      REPO_GOVERNANCE_BRANCH_PROTECTION_SCRIPT: "/definitely/missing/branch-protection.sh",
    }).status,
    1,
  );

  assert.match(
    runScenario(labelCreate, ["--apply"], { FAKE_GH_FAIL_LABEL_CREATE: "yes" }).stderr,
    /failed to create label/,
  );
});

test("apply preflight rejects bad auth, unreachable repositories, scopes and permissions", async () => {
  const harness = await createHarness();
  const unauthenticated = runScenario(harness, ["--apply"], { FAKE_GH_AUTH: "fail" });
  const unreachable = runScenario(harness, ["--apply"], { FAKE_GH_REPO_STATUS: "error" });
  const missingScope = runScenario(harness, ["--apply"], {
    FAKE_GH_SCOPES: "gist, read:org",
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
  assert.equal(await readLog(harness.mutationLog), "");
});

test("the drift workflow verifies weekly with a read token and publishes unknown items", async () => {
  const workflow = await readFile(DRIFT_WORKFLOW, "utf8");
  assert.match(workflow, /^name: Repository Governance Drift$/m);
  assert.match(workflow, /schedule:\n(?: {4}#[^\n]*\n)* {4}- cron: "0 7 \* \* 1"/);
  assert.match(workflow, /workflow_dispatch:/);
  assert.match(workflow, /GITHUB_TOKEN/);
  assert.match(workflow, /issues: read/);
  assert.match(
    workflow,
    /scripts\/apply-repo-governance\.sh --verify --repo "\$GITHUB_REPOSITORY"/,
  );
  assert.match(workflow, /## Repository governance drift/);
  assert.match(workflow, /--apply --prune/);
  assert.match(workflow, /UNKNOWN/);
  assert.match(workflow, /O24/);
});

test("CI runs bash -n, shellcheck, the coverage suite and a dry run", async () => {
  const workflow = await readFile(CI_WORKFLOW, "utf8");
  assert.match(workflow, /bash -n scripts\/apply-repo-governance\.sh/);
  assert.match(
    workflow,
    /shellcheck scripts\/apply-branch-protection\.sh scripts\/apply-repo-governance\.sh/,
  );
  assert.match(workflow, /node --test "scripts\/test\/apply-repo-governance\.test\.mjs"/);
  assert.match(
    workflow,
    /scripts\/apply-repo-governance\.sh --dry-run --repo "\$GITHUB_REPOSITORY"/,
  );
});

test("governance docs prove the tables, ownership and retirement lifecycle stay in sync", async () => {
  const governance = await readFile(GOVERNANCE, "utf8");
  const runbook = await readFile(RUNBOOK, "utf8");

  assert.match(governance, /settings\/repository-governance\.json/);
  assert.match(governance, /scripts\/apply-repo-governance\.sh --verify/);
  assert.match(governance, /repository-governance-drift\.yml/);
  assert.match(governance, /--prune/);
  assert.match(governance, /UNKNOWN/);
  assert.match(governance, /Retire a label without breaking existing issues/);
  assert.match(governance, /never deletes a milestone/);
  assert.match(runbook, /^## O24 — Repository label and milestone drift \(issue #180\)$/m);
  assert.match(runbook, /--apply --prune/);

  const labelRows = parseTable(governance, "| Label | Description | Color | Owner |");
  assert.deepEqual(
    labelRows,
    desired.labels.map((label) => [
      `\`${label.name}\``,
      label.description,
      `\`${label.color}\``,
      `\`${label.owner}\``,
    ]),
  );

  const milestoneRows = parseTable(governance, "| Milestone | Description | Owner |");
  assert.deepEqual(
    milestoneRows,
    desired.milestones.map((milestone) => [
      `\`${milestone.title}\``,
      milestone.description,
      `\`${milestone.owner}\``,
    ]),
  );
});

function parseTable(markdown, header) {
  const lines = markdown.split("\n");
  const start = lines.findIndex((line) => line.trim() === header);
  assert.notEqual(start, -1, `missing table header: ${header}`);
  const rows = [];
  for (let index = start + 2; index < lines.length; index += 1) {
    const line = lines[index].trim();
    if (!line.startsWith("|")) {
      break;
    }
    rows.push(
      line
        .split("|")
        .slice(1, -1)
        .map((cell) => cell.trim()),
    );
  }
  return rows;
}

test("every declared script path and error path is exercised", async () => {
  const listed = runScript(["--list-paths"], {
    REPO_GOVERNANCE_TRACE_FILE: SHARED_TRACE,
  })
    .stdout.trim()
    .split("\n");
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
