#!/usr/bin/env node
/**
 * Prove that a release may run for the checked-out commit (issue #179).
 *
 * The release workflow is triggered by `workflow_run` after CI finishes on
 * `main`. This script re-checks the event, branch, workflow name, conclusion and
 * commit SHA so a tag can only be created for a fully green CI run of the
 * current `main` commit.
 *
 * Usage:
 *   node scripts/release/verify-ci-gate.mjs \
 *     --event workflow_run --conclusion success --workflow-name CI \
 *     --workflow-event push --branch main \
 *     --sha <commit> --main-sha <commit> --github-output "$GITHUB_OUTPUT"
 *   node scripts/release/verify-ci-gate.mjs --list-paths
 */
import {
  UsageError,
  fail,
  listPaths,
  parseArgs,
  traceRecorder,
  writeGithubOutput,
} from "./cli.mjs";

const TRACE_PATHS = [
  "mode_help",
  "mode_list_paths",
  "mode_ci_verified",
  "mode_stale_sha",
  "mode_manual_release",
  "error_unknown_option",
  "error_missing_value",
  "error_unexpected_argument",
  "error_unknown_event",
  "error_wrong_workflow",
  "error_wrong_event",
  "error_wrong_branch",
  "error_ci_not_green",
  "error_invalid_sha",
];

const mark = traceRecorder(TRACE_PATHS, "RELEASE_TRACE_FILE");
const SHA_PATTERN = /^[0-9a-f]{40}$/i;

function usage() {
  console.log(`Usage: node scripts/release/verify-ci-gate.mjs [options]

Options:
  --event <name>           GitHub event name (workflow_run or workflow_dispatch).
  --conclusion <value>     CI conclusion (must be success).
  --workflow-name <name>   CI workflow name (must be CI).
  --workflow-event <name>  CI triggering event (must be push).
  --branch <name>          CI branch (must be main).
  --sha <sha>              Commit that CI verified.
  --main-sha <sha>         Current main commit.
  --github-output <path>   Write skip=true|false for the release step.
  --trace-file <path>      Append executed path ids (coverage gate).
  --list-paths             Print the coverage trace ids.
  --help                   Show this help.
`);
}

function requireValue(options, name) {
  const value = options[name];
  if (typeof value !== "string" || value.length === 0) {
    throw new UsageError("error_missing_value", `--${name} is required.`);
  }
  return value;
}

function main() {
  const options = parseArgs(process.argv.slice(2), {
    flags: ["help", "list-paths"],
    values: [
      "event",
      "conclusion",
      "workflow-name",
      "workflow-event",
      "branch",
      "sha",
      "main-sha",
      "github-output",
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

  const event = requireValue(options, "event");
  if (event === "workflow_dispatch") {
    console.log("Manual dispatch: the release job only runs from a green CI workflow_run.");
    writeGithubOutput(options["github-output"], { skip: "false" });
    mark("mode_manual_release");
    return;
  }
  if (event !== "workflow_run") {
    throw new UsageError(
      "error_unknown_event",
      `unsupported event '${event}'; expected workflow_run or workflow_dispatch.`,
    );
  }

  const conclusion = requireValue(options, "conclusion");
  const workflowName = requireValue(options, "workflow-name");
  const workflowEvent = requireValue(options, "workflow-event");
  const branch = requireValue(options, "branch");
  const sha = requireValue(options, "sha");
  const mainSha = requireValue(options, "main-sha");

  if (!SHA_PATTERN.test(sha)) {
    throw new UsageError("error_invalid_sha", `'${sha}' is not a full commit SHA.`);
  }
  if (!SHA_PATTERN.test(mainSha)) {
    throw new UsageError("error_invalid_sha", `'${mainSha}' is not a full commit SHA.`);
  }
  if (workflowName !== "CI") {
    throw new UsageError(
      "error_wrong_workflow",
      `the workflow run is '${workflowName}', not the full 'CI' workflow.`,
    );
  }
  if (workflowEvent !== "push") {
    throw new UsageError(
      "error_wrong_event",
      `the CI run was triggered by '${workflowEvent}', not by a push to main.`,
    );
  }
  if (branch !== "main") {
    throw new UsageError("error_wrong_branch", `the CI run is for '${branch}', not main.`);
  }
  if (conclusion !== "success") {
    throw new UsageError(
      "error_ci_not_green",
      `the CI run concluded with '${conclusion}'. A tag is only created after the full CI ` +
        "workflow passes; fix the failure and push again.",
    );
  }
  if (sha !== mainSha) {
    console.log(
      `main advanced from ${sha} to ${mainSha}; the next green CI run will release.`,
    );
    writeGithubOutput(options["github-output"], { skip: "true" });
    mark("mode_stale_sha");
    return;
  }

  console.log(`CI gate verified for ${sha} on main.`);
  writeGithubOutput(options["github-output"], { skip: "false" });
  mark("mode_ci_verified");
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
