import assert from "node:assert/strict";
import { test } from "node:test";
import {
  DEFAULT_ISSUE_TITLE,
  DEFAULT_JOB_NAME,
  ISSUE_MARKER,
  countConsecutiveFailures,
  findTrackingIssue,
  notifyFailure,
  notifySuccess,
  renderIssueBody,
  shouldNotify,
} from "../trivy-regression-notify.mjs";

function jsonResponse(payload, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (payload === undefined ? "" : JSON.stringify(payload)),
  };
}

function fakeGitHub(options = {}) {
  const {
    runs = [],
    jobsByRun = {},
    issues = [],
    comments = [],
    createdIssueNumber = 42,
  } = options;
  const calls = [];
  const fetchImpl = async (url, init = {}) => {
    const method = init.method ?? "GET";
    calls.push({
      url,
      method,
      body: init.body === undefined ? undefined : JSON.parse(init.body),
    });
    if (url.includes("/actions/workflows/") && url.includes("/runs?")) {
      return jsonResponse({ workflow_runs: runs });
    }
    const jobsMatch = url.match(/\/actions\/runs\/(\d+)\/jobs/);
    if (jobsMatch) {
      return jsonResponse({ jobs: jobsByRun[jobsMatch[1]] ?? [] });
    }
    if (/\/issues\?state=open/.test(url)) {
      return jsonResponse(issues);
    }
    if (url.includes("/comments") && method === "GET") {
      return jsonResponse(comments);
    }
    if (url.includes("/comments") && method === "POST") {
      return jsonResponse({ id: 1 });
    }
    if (/\/issues$/.test(url) && method === "POST") {
      return jsonResponse({ number: createdIssueNumber });
    }
    if (/\/issues\/\d+$/.test(url) && method === "PATCH") {
      return jsonResponse({ number: 1 });
    }
    throw new Error(`Unhandled ${method} ${url}`);
  };
  return { fetchImpl, calls };
}

const failureJobs = [{ name: DEFAULT_JOB_NAME, conclusion: "failure" }];
const successJobs = [{ name: DEFAULT_JOB_NAME, conclusion: "success" }];

const jacksonReport = {
  Results: [
    {
      Target: "backend/pom.xml",
      Vulnerabilities: [
        {
          VulnerabilityID: "CVE-2026-89407",
          PkgName: "com.fasterxml.jackson.core:jackson-databind",
          InstalledVersion: "2.20.0",
          FixedVersion: "2.21.7",
          Severity: "HIGH",
          Title: "Jackson databind unsafe deserialization",
        },
      ],
    },
  ],
};

test("the notification threshold only escalates repeated failures", () => {
  assert.equal(shouldNotify(1, 2), false);
  assert.equal(shouldNotify(2, 2), true);
  assert.equal(shouldNotify(3, 2), true);
});

test("consecutive failures stop at the first green Trivy job", async () => {
  const { fetchImpl, calls } = fakeGitHub({
    runs: [{ id: 103 }, { id: 102 }, { id: 101 }, { id: 100 }],
    jobsByRun: {
      103: failureJobs,
      102: failureJobs,
      101: successJobs,
      100: failureJobs,
    },
  });
  const result = await countConsecutiveFailures({
    fetchImpl,
    token: "token",
    repository: "owner/repo",
  });
  assert.equal(result.streak, 2);
  assert.equal(result.failedRuns.length, 2);
  assert.equal(
    calls.filter((call) => call.url.includes("/jobs")).length,
    3,
    "must stop inspecting after the first non-failure",
  );
});

test("the current run is excluded so it is counted only once", async () => {
  const { fetchImpl, calls } = fakeGitHub({
    runs: [{ id: 103 }, { id: 102 }],
    jobsByRun: { 103: failureJobs, 102: successJobs },
  });
  const result = await countConsecutiveFailures({
    fetchImpl,
    token: "token",
    repository: "owner/repo",
    excludeRunId: 103,
  });
  assert.equal(result.streak, 0);
  assert.equal(calls.some((call) => call.url.includes("103/jobs")), false);
});

test("the tracking issue is found by title and never matches a pull request", () => {
  const issue = { number: 7, state: "open", title: DEFAULT_ISSUE_TITLE };
  assert.equal(findTrackingIssue([issue]).number, 7);
  assert.equal(
    findTrackingIssue([{ ...issue, title: "another issue" }, { ...issue, pull_request: {} }]),
    undefined,
  );
});

test("the issue body contains the streak, run links and the Jackson floor context", () => {
  const body = renderIssueBody({
    streak: 2,
    threshold: 2,
    runUrl: "https://github.com/owner/repo/actions/runs/999",
    commitUrl: "https://github.com/owner/repo/commit/abc",
    report: jacksonReport,
  });
  assert.match(body, new RegExp(ISSUE_MARKER));
  assert.match(body, /Consecutive failing runs:\*\* 2/);
  assert.match(body, /actions\/runs\/999/);
  assert.match(body, /CVE-2026-89407/);
  assert.match(body, /2\.21\.7/);
});

test("the issue body does not claim PASS when the scan report is missing", () => {
  const shared = {
    streak: 2,
    threshold: 2,
    runUrl: "https://github.com/owner/repo/actions/runs/999",
    commitUrl: "https://github.com/owner/repo/commit/abc",
  };
  const explicit = renderIssueBody({ ...shared, reportAvailable: false });
  assert.match(explicit, /Scan report unavailable/);
  assert.doesNotMatch(explicit, /\*\*Result:\*\* ✅ PASS/);

  const nullReport = renderIssueBody({ ...shared, report: null });
  assert.match(nullReport, /Scan report unavailable/);
  assert.doesNotMatch(nullReport, /\*\*Result:\*\* ✅ PASS/);
});

test("the first failure does not open an issue", async () => {
  const { fetchImpl, calls } = fakeGitHub({
    runs: [{ id: 102 }],
    jobsByRun: { 102: successJobs },
  });
  const result = await notifyFailure({
    fetchImpl,
    token: "token",
    repository: "owner/repo",
    runId: 103,
    runUrl: "https://github.com/owner/repo/actions/runs/103",
    commitUrl: "https://github.com/owner/repo/commit/abc",
    report: jacksonReport,
    threshold: 2,
  });
  assert.equal(result.notified, false);
  assert.equal(result.streak, 1);
  assert.equal(calls.some((call) => call.url.includes("/issues")), false);
});

test("the second consecutive failure opens one tracking issue", async () => {
  const { fetchImpl, calls } = fakeGitHub({
    runs: [{ id: 102 }],
    jobsByRun: { 102: failureJobs },
    issues: [],
  });
  const result = await notifyFailure({
    fetchImpl,
    token: "token",
    repository: "owner/repo",
    runId: 103,
    runUrl: "https://github.com/owner/repo/actions/runs/103",
    commitUrl: "https://github.com/owner/repo/commit/abc",
    report: jacksonReport,
    threshold: 2,
  });
  assert.equal(result.notified, true);
  assert.equal(result.created, true);
  const created = calls.find((call) => call.method === "POST" && /\/issues$/.test(call.url));
  assert.equal(created.body.title, DEFAULT_ISSUE_TITLE);
  assert.match(created.body.body, /actions\/runs\/103/);
});

test("a repeated failure updates the existing issue and comments once per run", async () => {
  const issue = { number: 7, state: "open", title: DEFAULT_ISSUE_TITLE, body: "old" };
  const { fetchImpl, calls } = fakeGitHub({
    runs: [{ id: 102 }],
    jobsByRun: { 102: failureJobs },
    issues: [issue],
    comments: [],
  });
  const result = await notifyFailure({
    fetchImpl,
    token: "token",
    repository: "owner/repo",
    runId: 103,
    runUrl: "https://github.com/owner/repo/actions/runs/103",
    commitUrl: "https://github.com/owner/repo/commit/abc",
    report: jacksonReport,
    threshold: 2,
  });
  assert.equal(result.notified, true);
  assert.equal(result.created, false);
  assert.equal(result.issueNumber, 7);
  assert.ok(calls.some((call) => call.method === "PATCH" && call.url.endsWith("/issues/7")));
  const comment = calls.find((call) => call.method === "POST" && call.url.includes("/comments"));
  assert.match(comment.body.body, /streak 2/);
});

test("a green main run comments and closes the tracking issue", async () => {
  const issue = { number: 7, state: "open", title: DEFAULT_ISSUE_TITLE, body: "old" };
  const { fetchImpl, calls } = fakeGitHub({ issues: [issue], comments: [] });
  const result = await notifySuccess({
    fetchImpl,
    token: "token",
    repository: "owner/repo",
    runUrl: "https://github.com/owner/repo/actions/runs/200",
  });
  assert.equal(result.closed, true);
  assert.equal(result.issueNumber, 7);
  const close = calls.find((call) => call.method === "PATCH" && call.url.endsWith("/issues/7"));
  assert.equal(close.body.state, "closed");
  assert.equal(close.body.state_reason, "completed");
});
