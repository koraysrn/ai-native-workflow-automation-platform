#!/usr/bin/env node
/**
 * Repeated-failure notification for the Trivy CI gate (issue #162).
 *
 * Decision (ADR-0005): the regression signal is CI-only. A failing PR is already visible, so this
 * script only escalates failures that repeat on the default branch:
 *
 *   failure mode: after `--streak-threshold` consecutive failing `main` runs, open or update one
 *                 tracking issue and append a comment for the latest run.
 *   success mode: when the gate is green again, comment and close the tracking issue.
 *
 * The GitHub API client and the streak calculation take an injectable `fetchImpl` so the logic is
 * unit-tested without network access.
 */
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { findingsFromReport, renderSummary } from "./trivy-regression-report.mjs";

export const DEFAULT_ISSUE_TITLE = "security: Trivy gate failing repeatedly on main";
export const DEFAULT_JOB_NAME = "Trivy vulnerability scan";
export const ISSUE_MARKER = "<!-- awap-trivy-regression -->";

export function shouldNotify(streak, threshold = 2) {
  return Number(streak) >= Number(threshold);
}

export function findTrackingIssue(issues, title = DEFAULT_ISSUE_TITLE) {
  return (Array.isArray(issues) ? issues : []).find(
    (issue) => !issue.pull_request && issue.state === "open" && issue.title === title,
  );
}

function githubHeaders(token, hasBody) {
  return {
    Accept: "application/vnd.github+json",
    Authorization: `Bearer ${token}`,
    "X-GitHub-Api-Version": "2022-11-28",
    ...(hasBody ? { "Content-Type": "application/json" } : {}),
  };
}

async function githubRequest({ fetchImpl = fetch, token, url, method = "GET", body }) {
  const response = await fetchImpl(url, {
    method,
    headers: githubHeaders(token, body !== undefined),
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new Error(`GitHub API ${response.status} ${method} ${url}: ${text.slice(0, 500)}`);
  }
  if (response.status === 204) {
    return undefined;
  }
  const text = await response.text();
  return text.length > 0 ? JSON.parse(text) : undefined;
}

/**
 * Counts consecutive completed `main` runs whose Trivy job conclusion is `failure`, stopping at the
 * first non-failure. The current run is excluded because this step runs after the job has already
 * failed; `notifyFailure` adds it back to the streak.
 */
export async function countConsecutiveFailures(options = {}) {
  const {
    fetchImpl = fetch,
    token,
    repository,
    workflowFile = "ci.yml",
    branch = "main",
    jobName = DEFAULT_JOB_NAME,
    excludeRunId,
    perPage = 30,
  } = options;
  const [owner, repo] = String(repository ?? "").split("/");
  if (!owner || !repo) {
    throw new Error(`Invalid GITHUB_REPOSITORY: ${repository}`);
  }
  const base = `https://api.github.com/repos/${owner}/${repo}`;
  const runsUrl =
    `${base}/actions/workflows/${encodeURIComponent(workflowFile)}/runs` +
    `?branch=${encodeURIComponent(branch)}&status=completed&per_page=${perPage}`;
  const payload = await githubRequest({ fetchImpl, token, url: runsUrl });
  const runs = Array.isArray(payload?.workflow_runs) ? payload.workflow_runs : [];

  let streak = 0;
  const failedRuns = [];
  for (const run of runs) {
    if (String(run.id) === String(excludeRunId)) {
      continue;
    }
    const jobsUrl = `${base}/actions/runs/${run.id}/jobs?filter=latest&per_page=100`;
    let jobs;
    try {
      jobs = await githubRequest({ fetchImpl, token, url: jobsUrl });
    } catch (error) {
      // A transient API failure must not fabricate a streak.
      throw new Error(`Unable to inspect jobs for run ${run.id}: ${error.message}`);
    }
    const job = (jobs?.jobs ?? []).find((candidate) => candidate.name === jobName);
    if (!job) {
      break;
    }
    if (job.conclusion !== "failure") {
      break;
    }
    streak += 1;
    failedRuns.push(run);
  }
  return { streak, failedRuns };
}

export function renderIssueBody(options = {}) {
  const {
    streak,
    threshold = 2,
    runUrl,
    commitUrl,
    branch = "main",
    report = {},
    reportAvailable = true,
  } = options;
  let reportBlock;
  if (reportAvailable) {
    const findings = findingsFromReport(report);
    const summary = renderSummary(findings, { failOn: ["CRITICAL", "HIGH"] });
    const [summaryTitle, ...summaryBody] = summary.split("\n");
    reportBlock = [summaryTitle, "", ...summaryBody];
  } else {
    reportBlock = [
      "### Scan report unavailable",
      "",
      "The Trivy step did not produce `trivy-results.json`. Open the failed run log and investigate",
      "the scan itself before treating this as a dependency-regression signal.",
      "",
    ];
  }
  return [
    ISSUE_MARKER,
    `## Trivy gate is failing repeatedly on \`${branch}\``,
    "",
    `**Consecutive failing runs:** ${streak} (notification threshold: ${threshold})`,
    `**Latest run:** ${runUrl}`,
    `**Commit:** ${commitUrl}`,
    "",
    "The required `Trivy vulnerability scan` check is red on the default branch. This issue is",
    "opened once and updated for every further consecutive failure; a green `main` run comments and",
    "closes it automatically. Do not close it manually while the gate is red.",
    "",
    ...reportBlock,
  ].join("\n");
}

async function listOpenIssues({ fetchImpl, token, repository }) {
  const [owner, repo] = String(repository ?? "").split("/");
  const url = `https://api.github.com/repos/${owner}/${repo}/issues?state=open&per_page=100`;
  const issues = await githubRequest({ fetchImpl, token, url });
  return Array.isArray(issues) ? issues : [];
}

async function commentOnce({ fetchImpl, token, repository, issueNumber, marker, body }) {
  const [owner, repo] = String(repository ?? "").split("/");
  const commentsUrl = `https://api.github.com/repos/${owner}/${repo}/issues/${issueNumber}/comments?per_page=100`;
  const comments = await githubRequest({ fetchImpl, token, url: commentsUrl });
  const alreadyPresent = (Array.isArray(comments) ? comments : []).some((comment) =>
    String(comment.body ?? "").includes(marker),
  );
  if (alreadyPresent) {
    return false;
  }
  await githubRequest({
    fetchImpl,
    token,
    url: commentsUrl.split("?")[0],
    method: "POST",
    body: { body },
  });
  return true;
}

export async function notifyFailure(options = {}) {
  const {
    fetchImpl = fetch,
    token,
    repository,
    runId,
    runUrl,
    commitUrl,
    report = {},
    reportAvailable = true,
    threshold = 2,
    workflowFile = "ci.yml",
    jobName = DEFAULT_JOB_NAME,
    branch = "main",
    dryRun = false,
  } = options;
  const previous = await countConsecutiveFailures({
    fetchImpl,
    token,
    repository,
    workflowFile,
    jobName,
    branch,
    excludeRunId: runId,
  });
  const streak = previous.streak + 1;
  if (!shouldNotify(streak, threshold)) {
    console.log(
      `Trivy failure streak is ${streak}; the notification threshold is ${threshold}. ` +
        "The job summary and artifact are the signal for this run.",
    );
    return { notified: false, streak, created: false };
  }

  const issues = await listOpenIssues({ fetchImpl, token, repository });
  const existing = findTrackingIssue(issues);
  const body = renderIssueBody({
    streak,
    threshold,
    runUrl,
    commitUrl,
    branch,
    report,
    reportAvailable,
  });

  if (dryRun) {
    console.log(`[dry-run] Would ${existing ? "update" : "open"} the Trivy tracking issue.`);
    console.log(body);
    return { notified: true, dryRun: true, streak, created: false, issueNumber: existing?.number };
  }

  const [owner, repo] = String(repository ?? "").split("/");
  const base = `https://api.github.com/repos/${owner}/${repo}/issues`;

  if (existing) {
    await githubRequest({
      fetchImpl,
      token,
      url: `${base}/${existing.number}`,
      method: "PATCH",
      body: { body },
    });
    const commented = await commentOnce({
      fetchImpl,
      token,
      repository,
      issueNumber: existing.number,
      marker: runUrl,
      body: [
        `Another consecutive failing run on \`${branch}\` (streak ${streak}).`,
        "",
        `Run: ${runUrl}`,
        `Commit: ${commitUrl}`,
      ].join("\n"),
    });
    console.log(
      `Updated tracking issue #${existing.number} (streak ${streak})` +
        `${commented ? " with a new run comment" : ""}.`,
    );
    return { notified: true, created: false, issueNumber: existing.number, streak };
  }

  const created = await githubRequest({
    fetchImpl,
    token,
    url: base,
    method: "POST",
    body: { title: DEFAULT_ISSUE_TITLE, body },
  });
  console.log(`Opened tracking issue #${created.number} (streak ${streak}).`);
  return { notified: true, created: true, issueNumber: created.number, streak };
}

export async function notifySuccess(options = {}) {
  const {
    fetchImpl = fetch,
    token,
    repository,
    runUrl,
    branch = "main",
    dryRun = false,
  } = options;
  const issues = await listOpenIssues({ fetchImpl, token, repository });
  const existing = findTrackingIssue(issues);
  if (!existing) {
    console.log("No open Trivy tracking issue; nothing to close.");
    return { closed: false };
  }
  if (dryRun) {
    console.log(`[dry-run] Would comment on and close tracking issue #${existing.number}.`);
    return { closed: true, dryRun: true, issueNumber: existing.number };
  }

  const [owner, repo] = String(repository ?? "").split("/");
  const base = `https://api.github.com/repos/${owner}/${repo}/issues`;
  await commentOnce({
    fetchImpl,
    token,
    repository,
    issueNumber: existing.number,
    marker: runUrl,
    body: [
      `The Trivy gate is green again on \`${branch}\` (streak reset).`,
      "",
      `Run: ${runUrl}`,
    ].join("\n"),
  });
  await githubRequest({
    fetchImpl,
    token,
    url: `${base}/${existing.number}`,
    method: "PATCH",
    body: { state: "closed", state_reason: "completed" },
  });
  console.log(`Closed tracking issue #${existing.number} after a green ${branch} run.`);
  return { closed: true, issueNumber: existing.number };
}

function parseArgs(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (!argument.startsWith("--")) {
      continue;
    }
    const withoutPrefix = argument.slice(2);
    const equals = withoutPrefix.indexOf("=");
    if (equals >= 0) {
      args[withoutPrefix.slice(0, equals)] = withoutPrefix.slice(equals + 1);
      continue;
    }
    const next = argv[index + 1];
    if (next !== undefined && !next.startsWith("--")) {
      args[withoutPrefix] = next;
      index += 1;
    } else {
      args[withoutPrefix] = "true";
    }
  }
  return args;
}

export async function run(options = {}) {
  const argv = options.argv ?? process.argv.slice(2);
  const env = options.env ?? process.env;
  const args = parseArgs(argv);
  const mode = args.mode;
  if (mode !== "failure" && mode !== "success") {
    console.error("Usage: trivy-regression-notify.mjs --mode failure|success [options]");
    return 2;
  }

  const token = env.GITHUB_TOKEN ?? env.GH_TOKEN;
  const repository = args.repository ?? env.GITHUB_REPOSITORY;
  const runId = args["run-id"] ?? env.GITHUB_RUN_ID;
  const sha = args.sha ?? env.GITHUB_SHA;
  const serverUrl = (args["server-url"] ?? env.GITHUB_SERVER_URL ?? "https://github.com").replace(
    /\/$/,
    "",
  );
  if (!token || !repository) {
    console.error("GITHUB_TOKEN and GITHUB_REPOSITORY are required.");
    return 2;
  }
  const runUrl = `${serverUrl}/${repository}/actions/runs/${runId}`;
  const commitUrl = `${serverUrl}/${repository}/commit/${sha}`;
  const threshold = Number(args["streak-threshold"] ?? 2);
  const workflowFile = args["workflow-file"] ?? "ci.yml";
  const jobName = args["job-name"] ?? DEFAULT_JOB_NAME;
  const branch = args.branch ?? "main";
  const dryRun = args["dry-run"] === "true";

  if (mode === "success") {
    await notifySuccess({ token, repository, runUrl, branch, dryRun });
    return 0;
  }

  let report = {};
  let reportAvailable = true;
  const reportPath = args.report ?? "trivy-results.json";
  try {
    report = JSON.parse(await readFile(reportPath, "utf8"));
  } catch (error) {
    reportAvailable = false;
    console.error(`Unable to read ${reportPath}; notifying that the scan report is unavailable.`);
    console.error(error.message);
  }
  await notifyFailure({
    token,
    repository,
    runId,
    runUrl,
    commitUrl,
    report,
    reportAvailable,
    threshold,
    workflowFile,
    jobName,
    branch,
    dryRun,
  });
  return 0;
}

const invokedDirectly =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (invokedDirectly) {
  run()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error) => {
      console.error(error?.stack ?? String(error));
      process.exitCode = 2;
    });
}
