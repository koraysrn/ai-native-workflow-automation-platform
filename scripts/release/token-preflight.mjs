#!/usr/bin/env node
/**
 * Resolve and verify the token used by the release workflow (issue #179).
 *
 * The release job needs a token that can push tags, create releases and open or
 * update the release PR. A bot token other than GITHUB_TOKEN is required, because
 * pull requests created with GITHUB_TOKEN do not trigger CI and the release PR
 * would never receive its required checks. The preflight fails with an
 * actionable message before release-please runs, instead of letting the bot fail
 * halfway through a release.
 *
 * Usage:
 *   node scripts/release/token-preflight.mjs --repo owner/repo
 *   node scripts/release/token-preflight.mjs --repo owner/repo --github-output "$GITHUB_OUTPUT"
 *   node scripts/release/token-preflight.mjs --list-paths
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
  "mode_token_ok",
  "error_unknown_option",
  "error_missing_value",
  "error_unexpected_argument",
  "error_repo_missing",
  "error_missing_token",
  "error_api_unreachable",
  "error_unauthorized",
  "error_forbidden",
  "error_repo_not_found",
  "error_api_status",
  "error_invalid_response",
  "error_insufficient_permissions",
];

const mark = traceRecorder(TRACE_PATHS, "RELEASE_TRACE_FILE");

const REQUIRED_PERMISSIONS =
  "Contents: Read and write and Pull requests: Read and write " +
  "(classic tokens: the 'repo' scope)";

function usage() {
  console.log(`Usage: node scripts/release/token-preflight.mjs [options]

Verify that the release token can write to the repository.

Environment:
  RELEASE_PLEASE_TOKEN  Required bot token (fine-grained: Contents + Pull requests write).

Options:
  --repo <owner/repo>      Repository to verify (default: GITHUB_REPOSITORY).
  --api-url <url>          GitHub API base URL (default: https://api.github.com).
  --github-output <path>   Write token=<value> and token_source=<value>.
  --trace-file <path>      Append executed path ids (coverage gate).
  --list-paths             Print the coverage trace ids.
  --help                   Show this help.
`);
}

function classifyStatus(status) {
  if (status === 401) {
    return {
      pathId: "error_unauthorized",
      message:
        "the release token is invalid or expired. Set RELEASE_PLEASE_TOKEN to a valid " +
        `token with ${REQUIRED_PERMISSIONS}.`,
    };
  }
  if (status === 403) {
    return {
      pathId: "error_forbidden",
      message:
        "the release token is not allowed to read or write the repository. Grant it " +
        `${REQUIRED_PERMISSIONS}.`,
    };
  }
  if (status === 404) {
    return {
      pathId: "error_repo_not_found",
      message:
        "the repository is not visible to the release token. Check the repository name " +
        `and grant ${REQUIRED_PERMISSIONS}.`,
    };
  }
  return {
    pathId: "error_api_status",
    message: `the GitHub API returned HTTP ${status}; check the token and try again.`,
  };
}

async function main() {
  const options = parseArgs(process.argv.slice(2), {
    flags: ["help", "list-paths"],
    values: ["repo", "api-url", "github-output", "trace-file"],
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

  const repo = options.repo ?? process.env.GITHUB_REPOSITORY ?? "";
  if (!repo || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo)) {
    throw new UsageError(
      "error_repo_missing",
      "pass --repo owner/repo or set GITHUB_REPOSITORY.",
    );
  }

  const token = process.env.RELEASE_PLEASE_TOKEN ?? "";
  if (!token) {
    throw new UsageError(
      "error_missing_token",
      "RELEASE_PLEASE_TOKEN is required. Releases must be opened by a dedicated bot " +
        "identity (GitHub App or machine user), not GITHUB_TOKEN and not the " +
        "maintainer's personal PAT: a GITHUB_TOKEN release PR does not trigger CI and " +
        "a maintainer-authored release PR cannot self-approve. Store a fine-grained " +
        `token with ${REQUIRED_PERMISSIONS} as the RELEASE_PLEASE_TOKEN secret.`,
    );
  }

  const apiUrl = (options["api-url"] ?? "https://api.github.com").replace(/\/$/, "");
  let response;
  try {
    response = await fetch(`${apiUrl}/repos/${repo}`, {
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${token}`,
        "User-Agent": "awap-release-preflight",
        "X-GitHub-Api-Version": "2022-11-28",
      },
      signal: AbortSignal.timeout(15_000),
    });
  } catch (error) {
    throw new UsageError(
      "error_api_unreachable",
      `cannot reach ${apiUrl}: ${String(error.message ?? error)}.`,
    );
  }

  if (!response.ok) {
    const { pathId, message } = classifyStatus(response.status);
    throw new UsageError(pathId, message);
  }

  let body;
  try {
    body = await response.json();
  } catch {
    throw new UsageError(
      "error_invalid_response",
      `the GitHub API response for ${repo} is not valid JSON.`,
    );
  }

  if (body?.permissions?.push !== true) {
    throw new UsageError(
      "error_insufficient_permissions",
      `the release token cannot push to ${repo}; grant ${REQUIRED_PERMISSIONS}.`,
    );
  }

  console.log(`::add-mask::${token}`);
  writeGithubOutput(options["github-output"], {
    token,
    token_source: "RELEASE_PLEASE_TOKEN",
  });

  console.log(`Release token verified for ${repo}.`);
  mark("mode_token_ok");
}

try {
  await main();
} catch (error) {
  if (error instanceof UsageError) {
    mark(error.pathId);
    fail(error.message);
    process.exitCode = 1;
  } else {
    throw error;
  }
}
