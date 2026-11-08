#!/usr/bin/env node
/**
 * Validate every commit entering `main` with commitlint (issue #179).
 *
 * The local husky hook only checks the commit being authored. This script runs
 * in the required `Release Checks` CI job and lints the whole push/PR range, so
 * a non-conventional commit can never reach `main`.
 *
 * Usage:
 *   node scripts/release/lint-commits.mjs --from <sha> --to <sha>
 *   node scripts/release/lint-commits.mjs --commits-file commits.json
 *   node scripts/release/lint-commits.mjs --list-paths
 */
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  UsageError,
  fail,
  listPaths,
  parseArgs,
  traceRecorder,
} from "./cli.mjs";

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, "../..");

const TRACE_PATHS = [
  "mode_help",
  "mode_list_paths",
  "mode_lint_ok",
  "mode_no_commits",
  "error_unknown_option",
  "error_missing_value",
  "error_unexpected_argument",
  "error_conflicting_inputs",
  "error_commits_file_missing",
  "error_commits_file_invalid",
  "error_git_failed",
  "error_commitlint_missing",
  "error_commit_rejected",
];

const mark = traceRecorder(TRACE_PATHS, "RELEASE_TRACE_FILE");

function usage() {
  console.log(`Usage: node scripts/release/lint-commits.mjs [options]

Validate commit messages with commitlint.

Options:
  --from <sha>             First commit of the range (exclusive).
  --to <sha>               Last commit of the range (default: HEAD).
  --commits-file <path>    JSON commit list for tests/offline use.
  --commitlint-bin <path>  commitlint binary (default: node_modules/.bin/commitlint).
  --config <path>          commitlint config (default: commitlint.config.cjs).
  --trace-file <path>      Append executed path ids (coverage gate).
  --list-paths             Print the coverage trace ids.
  --help                   Show this help.
`);
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

function loadCommitsFromGit(from, to) {
  const allZero = /^0+$/.test(from ?? "");
  const range = !from || allZero ? to : `${from}..${to}`;
  const shas = git(["rev-list", "--no-merges", range])
    .split("\n")
    .filter(Boolean);
  return shas.map((sha) => ({
    sha,
    message: git(["show", "-s", "--format=%B", sha]),
  }));
}

function main() {
  const options = parseArgs(process.argv.slice(2), {
    flags: ["help", "list-paths"],
    values: ["from", "to", "commits-file", "commitlint-bin", "config", "trace-file"],
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

  const commitsFile = options["commits-file"];
  if (commitsFile && (options.from !== undefined || options.to !== undefined)) {
    throw new UsageError(
      "error_conflicting_inputs",
      "--commits-file cannot be combined with --from or --to.",
    );
  }

  const commits = commitsFile
    ? loadCommitsFromFile(commitsFile)
    : loadCommitsFromGit(options.from, options.to && options.to.length > 0 ? options.to : "HEAD");

  if (commits.length === 0) {
    console.log("No commits to lint.");
    mark("mode_no_commits");
    return;
  }

  const commitlintBin = options["commitlint-bin"] ??
    path.join(REPO_ROOT, "node_modules", ".bin", "commitlint");
  if (!existsSync(commitlintBin)) {
    throw new UsageError(
      "error_commitlint_missing",
      `commitlint binary '${commitlintBin}' is missing; run 'pnpm install' at the repository root.`,
    );
  }
  const config = options.config ?? path.join(REPO_ROOT, "commitlint.config.cjs");

  const rejected = [];
  for (const commit of commits) {
    const result = spawnSync(commitlintBin, ["--config", config], {
      cwd: REPO_ROOT,
      input: commit.message,
      encoding: "utf8",
      env: { ...process.env, FORCE_COLOR: "0", NO_COLOR: "1" },
    });
    if (result.error) {
      throw new UsageError(
        "error_commitlint_missing",
        `cannot execute commitlint '${commitlintBin}': ${result.error.message}`,
      );
    }
    if (result.status !== 0) {
      rejected.push({
        sha: commit.sha,
        subject: commit.message.split("\n")[0],
        output: `${result.stdout ?? ""}${result.stderr ?? ""}`.trim(),
      });
    }
  }

  if (rejected.length > 0) {
    for (const commit of rejected) {
      const detail = commit.output.split("\n").filter(Boolean).slice(-1)[0] ?? "invalid message";
      fail(
        `commit ${commit.sha} ('${commit.subject}') is not a Conventional Commit: ${detail}`,
      );
    }
    mark("error_commit_rejected");
    process.exitCode = 1;
    return;
  }

  console.log(`${commits.length} commit(s) follow Conventional Commits.`);
  mark("mode_lint_ok");
}

try {
  main();
} catch (error) {
  if (error instanceof UsageError) {
    mark(error.pathId);
    fail(error.message);
    process.exitCode = 1;
  } else {
    throw error;
  }
}
