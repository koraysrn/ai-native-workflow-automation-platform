#!/usr/bin/env node
/**
 * Print the next version and the CHANGELOG entries release-please would create.
 *
 * This is the dry-run mode required by issue #179. It never mutates a tag, a
 * release, the manifest or `CHANGELOG.md`; it only reads commits and the
 * release-please configuration.
 *
 * Usage:
 *   node scripts/release/dry-run.mjs --dry-run
 *   node scripts/release/dry-run.mjs --dry-run --format json
 *   node scripts/release/dry-run.mjs --dry-run --from v0.1.0 --to HEAD
 *   node scripts/release/dry-run.mjs --list-paths
 */
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  UsageError,
  fail,
  listPaths,
  parseArgs,
  traceRecorder,
  writeGithubOutput,
} from "./cli.mjs";
import {
  NonConventionalCommitError,
  computeBump,
  nextVersion,
  parseCommit,
  renderChangelogEntry,
  resolveSections,
} from "./conventional.mjs";

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, "../..");

const TRACE_PATHS = [
  "mode_help",
  "mode_list_paths",
  "mode_dry_run",
  "mode_dry_run_json",
  "mode_no_commits",
  "error_unknown_option",
  "error_missing_value",
  "error_unexpected_argument",
  "error_invalid_format",
  "error_manifest_missing",
  "error_manifest_invalid",
  "error_config_missing",
  "error_config_invalid",
  "error_package_json_invalid",
  "error_invalid_version",
  "error_commits_file_missing",
  "error_commits_file_invalid",
  "error_git_failed",
  "error_non_conventional",
];

const mark = traceRecorder(TRACE_PATHS, "RELEASE_TRACE_FILE");

function usage() {
  console.log(`Usage: node scripts/release/dry-run.mjs [options]

Print the next version and the CHANGELOG entries without releasing anything.

Options:
  --dry-run                 Explicitly request the dry run (this is the default).
  --from <ref>              First commit to inspect (default: last v* tag,
                            then bootstrap-sha, then the root commit).
  --to <ref>                Last commit to inspect (default: HEAD).
  --version <x.y.z>         Current version override (default: manifest/package.json).
  --commits-file <path>     JSON commit list for tests/offline use.
  --format <text|json>      Output format (default: text).
  --manifest <path>         .release-please-manifest.json path override.
  --config <path>           release-please-config.json path override.
  --package-json <path>     package.json path override.
  --trace-file <path>       Append executed path ids (coverage gate).
  --list-paths              Print the coverage trace ids.
  --help                    Show this help.
`);
}

function readJson(file, missingPath, invalidPath) {
  if (!existsSync(file)) {
    throw new UsageError(missingPath, `${path.relative(REPO_ROOT, file)} is missing.`);
  }
  try {
    return JSON.parse(readFileSync(file, "utf8"));
  } catch {
    throw new UsageError(invalidPath, `${path.relative(REPO_ROOT, file)} is not valid JSON.`);
  }
}

function git(args) {
  try {
    return execFileSync("git", args, {
      cwd: REPO_ROOT,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    }).trim();
  } catch (error) {
    const detail = String(error.stderr ?? error.message ?? "").trim();
    throw new UsageError(
      "error_git_failed",
      `git ${args.join(" ")} failed${detail ? `: ${detail}` : "."}`,
    );
  }
}

function defaultFrom(config) {
  try {
    const tag = git(["describe", "--tags", "--abbrev=0", "--match", "v[0-9]*"]);
    if (tag) {
      return tag;
    }
  } catch {
    // No v* tag yet: fall back to the manifest bootstrap sha.
  }
  const bootstrap = config?.packages?.["."]?.["bootstrap-sha"];
  if (typeof bootstrap === "string" && bootstrap.length > 0) {
    return bootstrap;
  }
  return git(["rev-list", "--max-parents=0", "HEAD"]).split("\n").pop();
}

function loadCommitsFromGit(from, to) {
  const range = from === to ? `${to}^..${to}` : `${from}..${to}`;
  let shas = "";
  try {
    shas = git(["rev-list", "--no-merges", range]);
  } catch (error) {
    if (from === to) {
      return [];
    }
    throw error;
  }
  return shas
    .split("\n")
    .filter(Boolean)
    .map((sha) => {
      const message = git(["show", "-s", "--format=%B", sha]);
      return { sha, message };
    });
}

function loadCommitsFromFile(file) {
  if (!existsSync(file)) {
    throw new UsageError("error_commits_file_missing", `commits file '${file}' does not exist.`);
  }
  let parsed;
  try {
    parsed = JSON.parse(readFileSync(file, "utf8"));
  } catch {
    throw new UsageError("error_commits_file_invalid", `commits file '${file}' is not valid JSON.`);
  }
  const entries = Array.isArray(parsed) ? parsed : parsed?.commits;
  if (!Array.isArray(entries)) {
    throw new UsageError(
      "error_commits_file_invalid",
      `commits file '${file}' must be an array or an object with a commits array.`,
    );
  }
  return entries.map((entry, index) => {
    if (typeof entry === "string") {
      return { sha: `file${index}`, message: entry };
    }
    if (entry && typeof entry.message === "string") {
      return { sha: String(entry.sha ?? `file${index}`), message: entry.message };
    }
    throw new UsageError(
      "error_commits_file_invalid",
      `entry ${index} in '${file}' must be a string or an object with a message string.`,
    );
  });
}

function main() {
  const options = parseArgs(process.argv.slice(2), {
    flags: ["dry-run", "help", "list-paths"],
    values: [
      "from",
      "to",
      "version",
      "commits-file",
      "format",
      "manifest",
      "config",
      "package-json",
      "trace-file",
    ],
  });

  if (options["trace-file"]) {
    process.env.RELEASE_TRACE_FILE = options["trace-file"];
  }
  if (options.help) {
    usage();
    mark("mode_help");
    return;
  }
  if (options["list-paths"]) {
    listPaths(TRACE_PATHS);
    mark("mode_list_paths");
    return;
  }

  const format = options.format ?? "text";
  if (!["text", "json"].includes(format)) {
    throw new UsageError("error_invalid_format", `unknown output format '${format}'.`);
  }

  const manifestPath = options.manifest ?? path.join(REPO_ROOT, ".release-please-manifest.json");
  const configPath = options.config ?? path.join(REPO_ROOT, "release-please-config.json");
  const packagePath = options["package-json"] ?? path.join(REPO_ROOT, "package.json");

  const manifest = readJson(
    manifestPath,
    "error_manifest_missing",
    "error_manifest_invalid",
  );
  const config = readJson(configPath, "error_config_missing", "error_config_invalid");

  let currentVersion = options.version;
  if (currentVersion === undefined) {
    currentVersion = manifest["."];
  }
  if (currentVersion === undefined) {
    const packageJson = readJson(
      packagePath,
      "error_manifest_missing",
      "error_package_json_invalid",
    );
    currentVersion = packageJson.version;
  }
  if (typeof currentVersion !== "string" || !/^\d+\.\d+\.\d+/.test(currentVersion)) {
    throw new UsageError(
      "error_invalid_version",
      `current version '${currentVersion}' is not a major.minor.patch version.`,
    );
  }

  const rawCommits = options["commits-file"]
    ? loadCommitsFromFile(options["commits-file"])
    : loadCommitsFromGit(
        options.from && options.from.length > 0 ? options.from : defaultFrom(config),
        options.to && options.to.length > 0 ? options.to : "HEAD",
      );

  const parsed = rawCommits.map((commit) => parseCommit(commit));
  const invalid = parsed.filter((commit) => !commit.conventional);
  if (invalid.length > 0) {
    throw new NonConventionalCommitError(invalid);
  }

  if (parsed.length === 0) {
    console.log(
      `No commits between the last release and HEAD; the current version remains ${currentVersion}.`,
    );
    mark("mode_no_commits");
    return;
  }

  const bump = computeBump(parsed);
  let version;
  try {
    version = nextVersion(currentVersion, bump);
  } catch (error) {
    throw new UsageError("error_invalid_version", error.message);
  }

  const changelog = renderChangelogEntry({
    version,
    date: new Date().toISOString().slice(0, 10),
    commits: parsed,
    sections: resolveSections(config["changelog-sections"]),
  });

  const plan = {
    currentVersion,
    nextVersion: version,
    bump,
    commits: parsed.map((commit) => ({
      sha: commit.sha,
      type: commit.type,
      scope: commit.scope,
      description: commit.description,
      breaking: commit.breaking,
    })),
    changelog,
  };

  if (format === "json") {
    console.log(JSON.stringify(plan, null, 2));
    mark("mode_dry_run_json");
    return;
  }

  console.log(`Current version: ${currentVersion}`);
  console.log(`Next version: ${version} (${bump})`);
  console.log(`Commits: ${parsed.length}`);
  console.log("");
  console.log(changelog);
  mark("mode_dry_run");
}

try {
  main();
} catch (error) {
  if (error instanceof UsageError) {
    mark(error.pathId);
    fail(error.message);
    process.exitCode = 1;
  } else if (error instanceof NonConventionalCommitError) {
    mark("error_non_conventional");
    for (const commit of error.commits) {
      fail(
        `commit ${commit.sha} does not follow Conventional Commits: '${commit.header}'. ` +
          "Amend or rebase the commit before releasing.",
      );
    }
    process.exitCode = 1;
  } else {
    throw error;
  }
}
