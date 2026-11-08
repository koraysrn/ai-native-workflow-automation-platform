import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import http from "node:http";
import { chmod, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const SCRIPTS = {
  lint: fileURLToPath(new URL("../release/lint-commits.mjs", import.meta.url)),
  guard: fileURLToPath(new URL("../release/changelog-guard.mjs", import.meta.url)),
  token: fileURLToPath(new URL("../release/token-preflight.mjs", import.meta.url)),
  gate: fileURLToPath(new URL("../release/verify-ci-gate.mjs", import.meta.url)),
};
const COMMITLINT_CONFIG = fileURLToPath(new URL("../../commitlint.config.cjs", import.meta.url));
const BASE_ENV = {
  PATH: process.env.PATH ?? "/usr/local/bin:/usr/bin:/bin",
  HOME: process.env.HOME ?? tmpdir(),
};

function run(script, args, env = {}) {
  return spawnSync(process.execPath, [SCRIPTS[script], ...args], {
    encoding: "utf8",
    env: { ...BASE_ENV, ...env },
  });
}

// The token tests run an HTTP server in this process. spawnSync would block the
// event loop and the server could never answer, so those tests use spawn.
function runAsync(script, args, env = {}) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [SCRIPTS[script], ...args], {
      env: { ...BASE_ENV, ...env },
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("close", (status) => resolve({ status, stdout, stderr }));
  });
}

const TRACE_DIR = await mkdtemp(path.join(tmpdir(), "awap-release-guard-trace-"));
async function trace(name) {
  const file = path.join(TRACE_DIR, `${name}.log`);
  await writeFile(file, "");
  return file;
}
const LINT_TRACE = await trace("lint");
const GUARD_TRACE = await trace("guard");
const TOKEN_TRACE = await trace("token");
const GATE_TRACE = await trace("gate");

const WORK_DIR = await mkdtemp(path.join(tmpdir(), "awap-release-guard-"));
const FAKE_COMMITLINT = path.join(WORK_DIR, "commitlint");
await writeFile(
  FAKE_COMMITLINT,
  '#!/bin/sh\ncat >/dev/null\nexit "${FAKE_COMMITLINT_EXIT:-0}"\n',
);
await chmod(FAKE_COMMITLINT, 0o755);

async function jsonFile(name, value) {
  const file = path.join(WORK_DIR, name);
  await writeFile(file, typeof value === "string" ? value : JSON.stringify(value));
  return file;
}

function traceContent(file) {
  return readFile(file, "utf8");
}

test("lint-commits accepts a conventional range", async () => {
  const commits = await jsonFile("lint-ok.json", [
    { sha: "a".repeat(40), message: "feat: valid" },
  ]);
  const result = run(
    "lint",
    ["--commits-file", commits, "--commitlint-bin", FAKE_COMMITLINT, "--config", COMMITLINT_CONFIG],
    { RELEASE_TRACE_FILE: LINT_TRACE },
  );
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /1 commit\(s\) follow Conventional Commits/);
});

test("lint-commits rejects a non-conventional commit", async () => {
  const commits = await jsonFile("lint-bad.json", [
    { sha: "b".repeat(40), message: "some changes" },
  ]);
  const result = run(
    "lint",
    ["--commits-file", commits, "--commitlint-bin", FAKE_COMMITLINT],
    { RELEASE_TRACE_FILE: LINT_TRACE, FAKE_COMMITLINT_EXIT: "1" },
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /::error::commit b{40} \('some changes'\) is not a Conventional Commit/);
});

test("lint-commits reports a missing commitlint binary", async () => {
  const commits = await jsonFile("lint-missing-bin.json", ["feat: valid"]);
  const result = run(
    "lint",
    ["--commits-file", commits, "--commitlint-bin", path.join(WORK_DIR, "missing-bin")],
    { RELEASE_TRACE_FILE: LINT_TRACE },
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /commitlint binary .* is missing/);
});

test("lint-commits reports missing and invalid commit files", async () => {
  const missing = run(
    "lint",
    ["--commits-file", path.join(WORK_DIR, "missing.json")],
    { RELEASE_TRACE_FILE: LINT_TRACE },
  );
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /does not exist/);

  const invalid = await jsonFile("lint-invalid.json", "{invalid");
  const invalidResult = run(
    "lint",
    ["--commits-file", invalid, "--commitlint-bin", FAKE_COMMITLINT],
    { RELEASE_TRACE_FILE: LINT_TRACE },
  );
  assert.equal(invalidResult.status, 1);
  assert.match(invalidResult.stderr, /not valid JSON/);

  const badEntry = await jsonFile("lint-bad-entry.json", [{ sha: "x" }]);
  const badEntryResult = run(
    "lint",
    ["--commits-file", badEntry, "--commitlint-bin", FAKE_COMMITLINT],
    { RELEASE_TRACE_FILE: LINT_TRACE },
  );
  assert.equal(badEntryResult.status, 1);
  assert.match(badEntryResult.stderr, /must be a string or an object with a message/);
});

test("lint-commits rejects conflicting inputs and failing git ranges", async () => {
  const conflicting = run(
    "lint",
    ["--commits-file", "commits.json", "--from", "HEAD"],
    { RELEASE_TRACE_FILE: LINT_TRACE },
  );
  assert.equal(conflicting.status, 1);
  assert.match(conflicting.stderr, /cannot be combined/);

  const failedGit = run(
    "lint",
    ["--from", "does-not-exist", "--to", "HEAD", "--commitlint-bin", FAKE_COMMITLINT],
    { RELEASE_TRACE_FILE: LINT_TRACE },
  );
  assert.equal(failedGit.status, 1);
  assert.match(failedGit.stderr, /git rev-list/);

  const empty = run(
    "lint",
    ["--from", "HEAD", "--to", "HEAD", "--commitlint-bin", FAKE_COMMITLINT],
    { RELEASE_TRACE_FILE: LINT_TRACE },
  );
  assert.equal(empty.status, 0);
  assert.match(empty.stdout, /No commits to lint/);

  const realRange = run(
    "lint",
    ["--from", "HEAD~1", "--to", "HEAD", "--commitlint-bin", FAKE_COMMITLINT],
    { RELEASE_TRACE_FILE: LINT_TRACE },
  );
  assert.equal(realRange.status, 0, realRange.stderr);
});

test("lint-commits help, list-paths and argument errors", () => {
  const traceEnv = { RELEASE_TRACE_FILE: LINT_TRACE };
  assert.equal(run("lint", ["--help"], traceEnv).status, 0);
  assert.match(run("lint", ["--list-paths"], traceEnv).stdout, /mode_lint_ok/);
  assert.match(run("lint", ["--bogus"], traceEnv).stderr, /unknown option/);
  assert.match(run("lint", ["--from"], traceEnv).stderr, /requires a value/);
  assert.match(run("lint", ["positional"], traceEnv).stderr, /unexpected argument/);
});

test("lint-commits exercises every trace path", async () => {
  const listed = run("lint", ["--list-paths"], { RELEASE_TRACE_FILE: LINT_TRACE })
    .stdout.split("\n")
    .filter(Boolean);
  const executed = new Set((await traceContent(LINT_TRACE)).split("\n").filter(Boolean));
  assert.deepEqual(listed.filter((pathId) => !executed.has(pathId)), []);
});

test("changelog-guard allows unchanged and bot-owned files", async () => {
  const unchanged = await jsonFile("changed-unchanged.txt", "README.md\n");
  const unchangedResult = run(
    "guard",
    ["--actor", "alice", "--branch", "feat/x", "--changed-files-file", unchanged],
    { RELEASE_TRACE_FILE: GUARD_TRACE },
  );
  assert.equal(unchangedResult.status, 0);

  const changed = await jsonFile("changed-changelog.txt", "CHANGELOG.md\n");
  const releaseBranch = run(
    "guard",
    [
      "--actor",
      "release-please[bot]",
      "--branch",
      "release-please--branches--main",
      "--changed-files-file",
      changed,
    ],
    { RELEASE_TRACE_FILE: GUARD_TRACE },
  );
  assert.equal(releaseBranch.status, 0);
  assert.match(releaseBranch.stdout, /Allowed/);

  const botActor = run(
    "guard",
    [
      "--actor",
      "renovate[bot]",
      "--branch",
      "renovate/deps",
      "--changed-files-file",
      changed,
    ],
    { RELEASE_TRACE_FILE: GUARD_TRACE },
  );
  assert.equal(botActor.status, 0);
});

test("changelog-guard rejects a manual changelog edit", async () => {
  const changed = await jsonFile("changed-manual.txt", "CHANGELOG.md\nsrc/index.ts\n");
  const result = run(
    "guard",
    [
      "--actor",
      "alice",
      "--branch",
      "feat/manual",
      "--changed-files-file",
      changed,
    ],
    { RELEASE_TRACE_FILE: GUARD_TRACE },
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /generated exclusively by release-please/);
});

test("changelog-guard skips other events and reports bad input", async () => {
  const push = run(
    "guard",
    ["--event", "push", "--actor", "alice", "--branch", "main"],
    { RELEASE_TRACE_FILE: GUARD_TRACE },
  );
  assert.equal(push.status, 0);
  assert.match(push.stdout, /does not run/);

  const missingContext = run(
    "guard",
    ["--changed-files-file", "whatever"],
    { RELEASE_TRACE_FILE: GUARD_TRACE },
  );
  assert.equal(missingContext.status, 1);
  assert.match(missingContext.stderr, /--actor and --branch are required/);

  const missingFile = run(
    "guard",
    ["--actor", "alice", "--branch", "feat/x", "--changed-files-file", path.join(WORK_DIR, "nope")],
    { RELEASE_TRACE_FILE: GUARD_TRACE },
  );
  assert.equal(missingFile.status, 1);
  assert.match(missingFile.stderr, /does not exist/);

  const invalid = await jsonFile("changed-invalid.txt", "[not json");
  const invalidResult = run(
    "guard",
    ["--actor", "alice", "--branch", "feat/x", "--changed-files-file", invalid],
    { RELEASE_TRACE_FILE: GUARD_TRACE },
  );
  assert.equal(invalidResult.status, 1);
  assert.match(invalidResult.stderr, /not valid JSON/);

  const objects = await jsonFile("changed-objects.txt", [{ filename: "README.md" }]);
  const objectsResult = run(
    "guard",
    ["--actor", "alice", "--branch", "feat/x", "--changed-files-file", objects],
    { RELEASE_TRACE_FILE: GUARD_TRACE },
  );
  assert.equal(objectsResult.status, 0);

  assert.equal(run("guard", ["--help"], { RELEASE_TRACE_FILE: GUARD_TRACE }).status, 0);
  assert.match(run("guard", ["--list-paths"], { RELEASE_TRACE_FILE: GUARD_TRACE }).stdout, /mode_guard_bot/);
  assert.match(run("guard", ["--bogus"], { RELEASE_TRACE_FILE: GUARD_TRACE }).stderr, /unknown option/);
  assert.match(run("guard", ["--actor"], { RELEASE_TRACE_FILE: GUARD_TRACE }).stderr, /requires a value/);
  assert.match(run("guard", ["positional"], { RELEASE_TRACE_FILE: GUARD_TRACE }).stderr, /unexpected argument/);
});

test("changelog-guard exercises every trace path", async () => {
  const listed = run("guard", ["--list-paths"], { RELEASE_TRACE_FILE: GUARD_TRACE })
    .stdout.split("\n")
    .filter(Boolean);
  const executed = new Set((await traceContent(GUARD_TRACE)).split("\n").filter(Boolean));
  assert.deepEqual(listed.filter((pathId) => !executed.has(pathId)), []);
});

async function withServer(handler, callback) {
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  try {
    return await callback(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

function respond(status, body, contentType = "application/json") {
  return (_request, response) => {
    response.writeHead(status, { "content-type": contentType });
    response.end(typeof body === "string" ? body : JSON.stringify(body));
  };
}

test("token-preflight accepts the preferred token and writes the output", async () => {
  const output = path.join(WORK_DIR, "token-output-1.txt");
  await writeFile(output, "");
  await withServer(respond(200, { permissions: { push: true } }), async (apiUrl) => {
    const result = await runAsync(
      "token",
      ["--repo", "owner/repo", "--api-url", apiUrl, "--github-output", output],
      { RELEASE_TRACE_FILE: TOKEN_TRACE, RELEASE_PLEASE_TOKEN: "pat-token" },
    );
    assert.equal(result.status, 0, result.stderr);
    const written = await readFile(output, "utf8");
    assert.match(written, /token=pat-token/);
    assert.match(written, /token_source=RELEASE_PLEASE_TOKEN/);
  });
});

test("token-preflight reports missing token and repository", () => {
  const missingToken = run(
    "token",
    ["--repo", "owner/repo"],
    { RELEASE_TRACE_FILE: TOKEN_TRACE },
  );
  assert.equal(missingToken.status, 1);
  assert.match(missingToken.stderr, /RELEASE_PLEASE_TOKEN is required/);

  const missingRepo = run("token", [], { RELEASE_TRACE_FILE: TOKEN_TRACE });
  assert.equal(missingRepo.status, 1);
  assert.match(missingRepo.stderr, /--repo owner\/repo/);
});

test("token-preflight maps HTTP and network failures to actionable errors", async () => {
  const tokenEnv = { RELEASE_TRACE_FILE: TOKEN_TRACE, RELEASE_PLEASE_TOKEN: "pat" };
  await withServer(respond(401, {}), async (apiUrl) => {
    const result = await runAsync("token", ["--repo", "owner/repo", "--api-url", apiUrl], tokenEnv);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /invalid or expired/);
  });
  await withServer(respond(403, {}), async (apiUrl) => {
    const result = await runAsync("token", ["--repo", "owner/repo", "--api-url", apiUrl], tokenEnv);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /not allowed to read or write/);
  });
  await withServer(respond(404, {}), async (apiUrl) => {
    const result = await runAsync("token", ["--repo", "owner/repo", "--api-url", apiUrl], tokenEnv);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /not visible/);
  });
  await withServer(respond(500, {}), async (apiUrl) => {
    const result = await runAsync("token", ["--repo", "owner/repo", "--api-url", apiUrl], tokenEnv);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /HTTP 500/);
  });
  await withServer(respond(200, "not json"), async (apiUrl) => {
    const result = await runAsync("token", ["--repo", "owner/repo", "--api-url", apiUrl], tokenEnv);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /not valid JSON/);
  });
  await withServer(respond(200, { permissions: { push: false } }), async (apiUrl) => {
    const result = await runAsync("token", ["--repo", "owner/repo", "--api-url", apiUrl], tokenEnv);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /cannot push/);
  });

  const unreachable = await runAsync(
    "token",
    ["--repo", "owner/repo", "--api-url", "http://127.0.0.1:1"],
    tokenEnv,
  );
  assert.equal(unreachable.status, 1);
  assert.match(unreachable.stderr, /cannot reach/);
});

test("token-preflight help, list-paths and argument errors", () => {
  const traceEnv = { RELEASE_TRACE_FILE: TOKEN_TRACE };
  assert.equal(run("token", ["--help"], traceEnv).status, 0);
  assert.match(run("token", ["--list-paths"], traceEnv).stdout, /mode_token_ok/);
  assert.match(run("token", ["--bogus"], traceEnv).stderr, /unknown option/);
  assert.match(run("token", ["--repo"], traceEnv).stderr, /requires a value/);
  assert.match(run("token", ["positional"], traceEnv).stderr, /unexpected argument/);
});

test("token-preflight exercises every trace path", async () => {
  const listed = run("token", ["--list-paths"], { RELEASE_TRACE_FILE: TOKEN_TRACE })
    .stdout.split("\n")
    .filter(Boolean);
  const executed = new Set((await traceContent(TOKEN_TRACE)).split("\n").filter(Boolean));
  assert.deepEqual(listed.filter((pathId) => !executed.has(pathId)), []);
});

const SHA = "1".repeat(40);

function gateArgs(overrides = {}) {
  const values = {
    event: "workflow_run",
    conclusion: "success",
    "workflow-name": "CI",
    "workflow-event": "push",
    branch: "main",
    sha: SHA,
    "main-sha": SHA,
    ...overrides,
  };
  return Object.entries(values).flatMap(([key, value]) => [`--${key}`, value]);
}

test("verify-ci-gate accepts a green CI run of the current main", async () => {
  const output = path.join(WORK_DIR, "gate-output-1.txt");
  await writeFile(output, "");
  const result = run(
    "gate",
    [...gateArgs(), "--github-output", output],
    { RELEASE_TRACE_FILE: GATE_TRACE },
  );
  assert.equal(result.status, 0, result.stderr);
  assert.match(await readFile(output, "utf8"), /skip=false/);
});

test("verify-ci-gate skips a stale main commit", async () => {
  const output = path.join(WORK_DIR, "gate-output-2.txt");
  await writeFile(output, "");
  const result = run(
    "gate",
    [...gateArgs({ "main-sha": "2".repeat(40) }), "--github-output", output],
    { RELEASE_TRACE_FILE: GATE_TRACE },
  );
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /main advanced/);
  assert.match(await readFile(output, "utf8"), /skip=true/);
});

test("verify-ci-gate allows a manual dry-run dispatch", () => {
  const result = run("gate", ["--event", "workflow_dispatch"], {
    RELEASE_TRACE_FILE: GATE_TRACE,
  });
  assert.equal(result.status, 0);
  assert.match(result.stdout, /Manual dispatch/);
});

test("verify-ci-gate rejects wrong workflow, event, branch and conclusion", () => {
  const cases = [
    [{ "workflow-name": "Docker Build" }, /not the full 'CI' workflow/],
    [{ "workflow-event": "pull_request" }, /not by a push to main/],
    [{ branch: "feature/x" }, /not main/],
    [{ conclusion: "failure" }, /only created after the full CI workflow passes/],
  ];
  for (const [overrides, pattern] of cases) {
    const result = run("gate", gateArgs(overrides), { RELEASE_TRACE_FILE: GATE_TRACE });
    assert.equal(result.status, 1);
    assert.match(result.stderr, pattern);
  }
});

test("verify-ci-gate reports unknown events, invalid SHAs and missing values", () => {
  const traceEnv = { RELEASE_TRACE_FILE: GATE_TRACE };
  const unknown = run("gate", ["--event", "push"], traceEnv);
  assert.equal(unknown.status, 1);
  assert.match(unknown.stderr, /unsupported event/);

  const invalidSha = run("gate", gateArgs({ sha: "abc" }), traceEnv);
  assert.equal(invalidSha.status, 1);
  assert.match(invalidSha.stderr, /not a full commit SHA/);

  const missing = run(
    "gate",
    ["--event", "workflow_run", "--conclusion", "success"],
    traceEnv,
  );
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /--workflow-name is required/);
});

test("verify-ci-gate help, list-paths and argument errors", () => {
  const traceEnv = { RELEASE_TRACE_FILE: GATE_TRACE };
  assert.equal(run("gate", ["--help"], traceEnv).status, 0);
  assert.match(run("gate", ["--list-paths"], traceEnv).stdout, /mode_ci_verified/);
  assert.match(run("gate", ["--bogus"], traceEnv).stderr, /unknown option/);
  assert.match(run("gate", ["--event"], traceEnv).stderr, /requires a value/);
  assert.match(run("gate", ["positional"], traceEnv).stderr, /unexpected argument/);
});

test("verify-ci-gate exercises every trace path", async () => {
  const listed = run("gate", ["--list-paths"], { RELEASE_TRACE_FILE: GATE_TRACE })
    .stdout.split("\n")
    .filter(Boolean);
  const executed = new Set((await traceContent(GATE_TRACE)).split("\n").filter(Boolean));
  assert.deepEqual(listed.filter((pathId) => !executed.has(pathId)), []);
});
