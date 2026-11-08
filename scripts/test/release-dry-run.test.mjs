import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const SCRIPT = fileURLToPath(new URL("../release/dry-run.mjs", import.meta.url));
const BASE_ENV = {
  PATH: process.env.PATH ?? "/usr/local/bin:/usr/bin:/bin",
  HOME: process.env.HOME ?? tmpdir(),
};

function run(args, env = {}) {
  return spawnSync(process.execPath, [SCRIPT, ...args], {
    encoding: "utf8",
    env: { ...BASE_ENV, ...env },
  });
}

const TRACE_DIR = await mkdtemp(path.join(tmpdir(), "awap-release-dry-run-trace-"));
const TRACE_FILE = path.join(TRACE_DIR, "coverage.log");
await writeFile(TRACE_FILE, "");
const TRACE_ENV = { RELEASE_TRACE_FILE: TRACE_FILE };

async function fixture({
  manifest = { ".": "0.0.0" },
  config = {},
  packageJson = { version: "0.0.0" },
  commits = [{ sha: "a".repeat(40), message: "feat: add a feature" }],
  manifestText,
  configText,
  packageText,
  commitsText,
} = {}) {
  const directory = await mkdtemp(path.join(tmpdir(), "awap-release-dry-run-"));
  const manifestPath = path.join(directory, "manifest.json");
  const configPath = path.join(directory, "release-please-config.json");
  const packagePath = path.join(directory, "package.json");
  const commitsPath = path.join(directory, "commits.json");

  await writeFile(manifestPath, manifestText ?? JSON.stringify(manifest));
  await writeFile(configPath, configText ?? JSON.stringify(config));
  await writeFile(packagePath, packageText ?? JSON.stringify(packageJson));
  await writeFile(commitsPath, commitsText ?? JSON.stringify(commits));

  return {
    directory,
    manifestPath,
    configPath,
    packagePath,
    commitsPath,
    args: [
      "--manifest",
      manifestPath,
      "--config",
      configPath,
      "--package-json",
      packagePath,
    ],
  };
}

function trace() {
  return readFile(TRACE_FILE, "utf8");
}

test("help prints the usage", () => {
  const result = run(["--help"], TRACE_ENV);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /Usage: node scripts\/release\/dry-run\.mjs/);
});

test("list-paths prints every trace id", () => {
  const result = run(["--list-paths"], TRACE_ENV);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /mode_dry_run/m);
  assert.match(result.stdout, /error_non_conventional/m);
});

test("a feat commit produces a minor version and a Features entry", async () => {
  const present = await fixture({
    commits: [{ sha: "a".repeat(40), message: "feat(api): add endpoint" }],
  });
  const result = run(
    [...present.args, "--dry-run", "--commits-file", present.commitsPath],
    TRACE_ENV,
  );
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Next version: 0\.1\.0 \(minor\)/);
  assert.match(result.stdout, /### Features/);
  assert.match(result.stdout, /\*\*api:\*\* add endpoint/);
  assert.match(await trace(), /mode_dry_run/);
});

test("a fix commit produces a patch version", async () => {
  const present = await fixture({
    commits: [{ sha: "b".repeat(40), message: "fix(core): repair the bug" }],
  });
  const result = run([...present.args, "--commits-file", present.commitsPath], TRACE_ENV);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /Next version: 0\.0\.1 \(patch\)/);
  assert.match(result.stdout, /### Bug Fixes/);
});

test("a breaking change produces a major version in JSON", async () => {
  const present = await fixture({
    commits: [
      {
        sha: "c".repeat(40),
        message: "feat!: drop the v1 API\n\nBREAKING CHANGE: migrate to v2",
      },
    ],
  });
  const result = run(
    [...present.args, "--commits-file", present.commitsPath, "--format", "json"],
    TRACE_ENV,
  );
  assert.equal(result.status, 0, result.stderr);
  const plan = JSON.parse(result.stdout);
  assert.equal(plan.currentVersion, "0.0.0");
  assert.equal(plan.nextVersion, "1.0.0");
  assert.equal(plan.bump, "major");
  assert.match(plan.changelog, /BREAKING CHANGES/);
  assert.match(await trace(), /mode_dry_run_json/);
});

test("hidden changelog sections are not printed", async () => {
  const present = await fixture({
    commits: [{ sha: "d".repeat(40), message: "chore: tidy the build" }],
  });
  const result = run([...present.args, "--commits-file", present.commitsPath], TRACE_ENV);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /Next version: 0\.0\.1/);
  assert.doesNotMatch(result.stdout, /Miscellaneous Chores/);
});

test("a non-conventional commit is rejected", async () => {
  const present = await fixture({
    commits: [{ sha: "e".repeat(40), message: "just some changes" }],
  });
  const result = run([...present.args, "--commits-file", present.commitsPath], TRACE_ENV);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /::error::commit e{40} does not follow Conventional Commits/);
  assert.match(await trace(), /error_non_conventional/);
});

test("an empty commit list reports that no release is needed", async () => {
  const present = await fixture({ commits: [] });
  const result = run([...present.args, "--commits-file", present.commitsPath], TRACE_ENV);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /No commits/);
  assert.match(await trace(), /mode_no_commits/);
});

test("the git range mode reads real commits", async () => {
  const present = await fixture();
  const result = run(
    [...present.args, "--dry-run", "--from", "HEAD~1", "--to", "HEAD"],
    TRACE_ENV,
  );
  assert.equal(result.status, 0, result.stderr);
  assert.match(await trace(), /mode_dry_run/);
});

test("a failing git range is reported", async () => {
  const present = await fixture();
  const result = run(
    [...present.args, "--from", "does-not-exist", "--to", "HEAD"],
    TRACE_ENV,
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /::error::git rev-list/);
  assert.match(await trace(), /error_git_failed/);
});

test("a missing commits file is reported", async () => {
  const present = await fixture();
  const result = run(
    [...present.args, "--commits-file", path.join(present.directory, "missing.json")],
    TRACE_ENV,
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /does not exist/);
  assert.match(await trace(), /error_commits_file_missing/);
});

test("an invalid commits file is reported", async () => {
  const present = await fixture({ commitsText: "{not json" });
  const result = run([...present.args, "--commits-file", present.commitsPath], TRACE_ENV);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /not valid JSON/);
  assert.match(await trace(), /error_commits_file_invalid/);
});

test("a commits file entry without a message is reported", async () => {
  const present = await fixture({ commitsText: JSON.stringify([{ sha: "x" }]) });
  const result = run([...present.args, "--commits-file", present.commitsPath], TRACE_ENV);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /must be a string or an object with a message/);
  assert.match(await trace(), /error_commits_file_invalid/);
});

test("a missing manifest is reported", async () => {
  const present = await fixture();
  const result = run(
    [
      "--manifest",
      path.join(present.directory, "missing-manifest.json"),
      "--config",
      present.configPath,
    ],
    TRACE_ENV,
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /missing-manifest\.json is missing/);
  assert.match(await trace(), /error_manifest_missing/);
});

test("an invalid manifest is reported", async () => {
  const present = await fixture({ manifestText: "{invalid" });
  const result = run(
    [...present.args, "--commits-file", present.commitsPath],
    TRACE_ENV,
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /not valid JSON/);
  assert.match(await trace(), /error_manifest_invalid/);
});

test("a missing config is reported", async () => {
  const present = await fixture();
  const result = run(
    [
      "--manifest",
      present.manifestPath,
      "--config",
      path.join(present.directory, "missing-config.json"),
    ],
    TRACE_ENV,
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /missing-config\.json is missing/);
  assert.match(await trace(), /error_config_missing/);
});

test("an invalid config is reported", async () => {
  const present = await fixture({ configText: "{invalid" });
  const result = run([...present.args, "--commits-file", present.commitsPath], TRACE_ENV);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /not valid JSON/);
  assert.match(await trace(), /error_config_invalid/);
});

test("an invalid package.json is reported when the manifest has no version", async () => {
  const present = await fixture({
    manifest: {},
    packageText: "{invalid",
  });
  const result = run([...present.args, "--commits-file", present.commitsPath], TRACE_ENV);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /package\.json is not valid JSON/);
  assert.match(await trace(), /error_package_json_invalid/);
});

test("a missing package.json is reported when the manifest has no version", async () => {
  const present = await fixture({ manifest: {} });
  const result = run(
    [
      ...present.args,
      "--package-json",
      path.join(present.directory, "missing-package.json"),
      "--commits-file",
      present.commitsPath,
    ],
    TRACE_ENV,
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /package\.json is missing/);
  assert.match(await trace(), /error_package_json_missing/);
});

test("an empty git range reports no commits", async () => {
  const present = await fixture();
  const result = run([...present.args, "--from", "HEAD", "--to", "HEAD"], TRACE_ENV);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /No commits/);
});

test("an invalid current version is reported", async () => {
  const present = await fixture();
  const result = run(
    [...present.args, "--version", "1.x", "--commits-file", present.commitsPath],
    TRACE_ENV,
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /not a major\.minor\.patch version/);
  assert.match(await trace(), /error_invalid_version/);
});

test("an invalid output format is reported", () => {
  const result = run(["--format", "yaml"], TRACE_ENV);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /unknown output format/);
});

test("unknown options, missing values and unexpected arguments are reported", () => {
  const unknown = run(["--bogus"], TRACE_ENV);
  assert.equal(unknown.status, 1);
  assert.match(unknown.stderr, /unknown option '--bogus'/);

  const missing = run(["--from"], TRACE_ENV);
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /--from requires a value/);

  const unexpected = run(["positional"], TRACE_ENV);
  assert.equal(unexpected.status, 1);
  assert.match(unexpected.stderr, /unexpected argument 'positional'/);
});

test("every declared trace path is exercised", async () => {
  const listed = run(["--list-paths"], TRACE_ENV).stdout.split("\n").filter(Boolean);
  const executed = new Set((await trace()).split("\n").filter(Boolean));
  const missing = listed.filter((pathId) => !executed.has(pathId));
  assert.deepEqual(missing, []);
  for (const pathId of ["error_unknown_option", "error_missing_value", "error_unexpected_argument", "error_invalid_format"]) {
    assert.ok(executed.has(pathId), `${pathId} was not exercised`);
  }
});
