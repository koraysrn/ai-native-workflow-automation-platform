#!/usr/bin/env node
/**
 * Reject manual edits to CHANGELOG.md (issue #179).
 *
 * `CHANGELOG.md` is owned by release-please. A pull request may only change it
 * when it comes from the release-please branch. Every other pull request that
 * touches the file fails the required `Release Checks` job.
 *
 * Usage:
 *   node scripts/release/changelog-guard.mjs \
 *     --event pull_request --actor github-actions[bot] \
 *     --branch release-please--branches--main \
 *     --changed-files-file changed-files.txt
 *   node scripts/release/changelog-guard.mjs --list-paths
 */
import { existsSync, readFileSync } from "node:fs";
import {
  UsageError,
  fail,
  listPaths,
  parseArgs,
  traceRecorder,
} from "./cli.mjs";

const TRACE_PATHS = [
  "mode_help",
  "mode_list_paths",
  "mode_guard_skipped_event",
  "mode_guard_ok",
  "mode_guard_bot",
  "error_unknown_option",
  "error_missing_value",
  "error_unexpected_argument",
  "error_missing_context",
  "error_changed_files_missing",
  "error_changed_files_invalid",
  "error_manual_changelog_edit",
];

const mark = traceRecorder(TRACE_PATHS, "RELEASE_TRACE_FILE");

function usage() {
  console.log(`Usage: node scripts/release/changelog-guard.mjs [options]

Fail when a pull request that is not the release-please bot edits CHANGELOG.md.

Options:
  --event <name>              GitHub event name (default: pull_request).
  --actor <login>             Pull request author login.
  --branch <name>             Pull request head branch.
  --changed-files-file <path> File with the PR file names (JSON array or one per line).
  --changelog-path <path>     Guarded file (default: CHANGELOG.md).
  --trace-file <path>         Append executed path ids (coverage gate).
  --list-paths                Print the coverage trace ids.
  --help                      Show this help.
`);
}

function readChangedFiles(file) {
  if (!existsSync(file)) {
    throw new UsageError("error_changed_files_missing", `changed-files file '${file}' does not exist.`);
  }
  const raw = readFileSync(file, "utf8");
  const trimmed = raw.trim();
  if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
    try {
      const parsed = JSON.parse(trimmed);
      if (!Array.isArray(parsed)) {
        throw new Error("not an array");
      }
      return parsed.map((entry) =>
        typeof entry === "string" ? entry : String(entry?.filename ?? entry?.path ?? ""),
      );
    } catch {
      throw new UsageError(
        "error_changed_files_invalid",
        `changed-files file '${file}' is not valid JSON.`,
      );
    }
  }
  return trimmed
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
}

function main() {
  const options = parseArgs(process.argv.slice(2), {
    flags: ["help", "list-paths"],
    values: ["event", "actor", "branch", "changed-files-file", "changelog-path", "trace-file"],
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

  const event = options.event ?? "pull_request";
  if (event !== "pull_request") {
    console.log(`CHANGELOG guard does not run for the '${event}' event.`);
    mark("mode_guard_skipped_event");
    return;
  }

  const actor = options.actor ?? "";
  const branch = options.branch ?? "";
  if (!actor || !branch) {
    throw new UsageError(
      "error_missing_context",
      "--actor and --branch are required to identify the release-please pull request.",
    );
  }

  const changelogPath = options["changelog-path"] ?? "CHANGELOG.md";
  const changedFiles = readChangedFiles(options["changed-files-file"]);

  if (!changedFiles.includes(changelogPath)) {
    console.log(`${changelogPath} is unchanged; nothing to guard.`);
    mark("mode_guard_ok");
    return;
  }

  // The branch name is the reliable signal: with a GitHub App or machine-user
  // token the pull request author can be a human login, while release-please
  // always opens `release-please--branches--<base>`.
  if (branch.startsWith("release-please--")) {
    console.log(`Allowed: '${actor}' on '${branch}' is release automation.`);
    mark("mode_guard_bot");
    return;
  }

  throw new UsageError(
    "error_manual_changelog_edit",
    `${changelogPath} is generated exclusively by release-please. ` +
      "Revert the manual edit; the release PR will update the changelog. " +
      "See docs/release-runbook.md for the emergency procedure.",
  );
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
