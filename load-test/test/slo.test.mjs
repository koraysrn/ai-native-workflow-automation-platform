import assert from "node:assert/strict";
import { test } from "node:test";
import {
  buildReport,
  CARRY_ZERO_WHEN_ABSENT,
  checksFor,
  DRY_RUN_SUMMARY,
  evaluateSlo,
  metricValue,
  renderSummaryMarkdown,
  SLO_CHECKS,
  THROUGHPUT_CHECK,
} from "../lib/slo.mjs";

/** Healthy k6 summary fixture; individual tests overwrite one value to force a breach. */
function healthySummary() {
  return {
    metrics: {
      "http_req_duration{scenario:tenant_isolation}": { values: { "p(95)": 400, "p(99)": 900 } },
      "http_req_failed{scenario:tenant_isolation}": { values: { rate: 0 } },
      "http_req_duration{scenario:approval_race}": { values: { "p(95)": 300, "p(99)": 700 } },
      "http_req_failed{scenario:approval_race}": { values: { rate: 0.001 } },
      "http_req_duration{scenario:idempotent_start}": { values: { "p(95)": 350, "p(99)": 800 } },
      "http_req_failed{scenario:idempotent_start}": { values: { rate: 0 } },
      isolation_violations: { values: { count: 0 } },
      cross_tenant_leaks: { values: { count: 0 } },
      approval_race_violations: { values: { count: 0 } },
      idempotency_violations: { values: { count: 0 } },
      http_reqs: { values: { rate: 120 } },
    },
  };
}

test("metricValue reads only finite numeric stats", () => {
  const summary = healthySummary();
  assert.equal(metricValue(summary, "http_reqs", "rate"), 120);
  assert.equal(metricValue(summary, "http_reqs", "p(95)"), undefined);
  assert.equal(metricValue(summary, "missing", "rate"), undefined);
  assert.equal(metricValue(undefined, "http_reqs", "rate"), undefined);
  assert.equal(metricValue({ metrics: { m: {} } }, "m", "rate"), undefined);
  assert.equal(
    metricValue({ metrics: { m: { values: { rate: Number.NaN } } } }, "m", "rate"),
    undefined,
  );
  // `--summary-export` writes stats inline rather than under `values`.
  assert.equal(metricValue({ metrics: { m: { "p(99)": 42 } } }, "m", "p(99)"), 42);
});

test("a healthy run passes every SLO", () => {
  const evaluation = evaluateSlo(healthySummary());
  assert.equal(evaluation.pass, true);
  assert.equal(evaluation.regressions.length, 0);
  assert.equal(evaluation.checks.length, SLO_CHECKS.length);
  assert.equal(checksFor("full").length, SLO_CHECKS.length + 1);
  assert.equal(checksFor("smoke").length, SLO_CHECKS.length);
  assert.ok(!checksFor("smoke").includes(THROUGHPUT_CHECK));
});

test("a p95 regression is reported with its metric and budget", () => {
  const summary = healthySummary();
  summary.metrics["http_req_duration{scenario:tenant_isolation}"].values["p(95)"] = 2500;
  const evaluation = evaluateSlo(summary);
  assert.equal(evaluation.pass, false);
  assert.deepEqual(
    evaluation.regressions.map((regression) => regression.id),
    ["tenant_isolation_p95"],
  );
  assert.equal(evaluation.regressions[0].reason, "budget-exceeded");
  assert.equal(evaluation.regressions[0].actual, 2500);
  assert.equal(evaluation.regressions[0].target, 2000);
});

test("a p99 regression is independent of p95", () => {
  const summary = healthySummary();
  summary.metrics["http_req_duration{scenario:idempotent_start}"].values["p(99)"] = 4100;
  const evaluation = evaluateSlo(summary);
  assert.equal(evaluation.pass, false);
  assert.deepEqual(
    evaluation.regressions.map((regression) => regression.id),
    ["idempotent_start_p99"],
  );
});

test("an error-rate budget and a correctness counter both fail the run", () => {
  const summary = healthySummary();
  summary.metrics["http_req_failed{scenario:approval_race}"].values.rate = 0.2;
  summary.metrics.approval_race_violations.values.count = 1;
  const evaluation = evaluateSlo(summary);
  assert.deepEqual(
    evaluation.regressions.map((regression) => regression.id),
    ["approval_race_error_rate", "approval_race_violations"],
  );
});

test("throughput is a full-profile lower bound", () => {
  const summary = healthySummary();
  summary.metrics.http_reqs.values.rate = 5;
  const evaluation = evaluateSlo(summary, checksFor("full"));
  assert.deepEqual(
    evaluation.regressions.map((regression) => regression.id),
    ["throughput"],
  );
  assert.equal(evaluation.regressions[0].reason, "budget-exceeded");
  // A smoke run is not gated on throughput: it deliberately uses three VUs with think time.
  assert.equal(evaluateSlo(summary, checksFor("smoke")).pass, true);
});

test("an absent counter or zero error rate is a pass; an absent latency is a failure", () => {
  const summary = healthySummary();
  for (const key of CARRY_ZERO_WHEN_ABSENT) {
    delete summary.metrics[key.split("|")[0]];
  }
  const zeroed = evaluateSlo(summary).checks.filter((check) =>
    CARRY_ZERO_WHEN_ABSENT.has(`${check.metric}|${check.stat}`),
  );
  assert.equal(zeroed.length, CARRY_ZERO_WHEN_ABSENT.size);
  assert.ok(zeroed.every((check) => check.pass));
  assert.ok(zeroed.every((check) => check.actual === 0));
  assert.ok(zeroed.every((check) => check.observed === false));

  delete summary.metrics["http_req_duration{scenario:tenant_isolation}"];
  const evaluation = evaluateSlo(summary);
  assert.equal(evaluation.pass, false);
  const missing = evaluation.regressions.find(
    (regression) => regression.id === "tenant_isolation_p95",
  );
  assert.equal(missing.reason, "metric-missing");
  assert.equal(missing.actual, null);
});

test("the embedded dry-run summary passes every check", () => {
  assert.equal(evaluateSlo(DRY_RUN_SUMMARY).pass, true);
  assert.equal(evaluateSlo(DRY_RUN_SUMMARY, checksFor("full")).pass, true);
});

test("buildReport records the verdict, thresholds and regressions", () => {
  const summary = healthySummary();
  summary.metrics["http_req_duration{scenario:approval_race}"].values["p(99)"] = 9000;
  const report = buildReport({
    summary,
    mode: "full",
    tenants: 1000,
    apiBase: "http://localhost:8080",
    source: "load-test/results/tenancy-summary.json",
    generatedAt: "2026-11-11T00:00:00.000Z",
  });
  assert.equal(report.result, "fail");
  assert.equal(report.target.tenants, 1000);
  assert.equal(report.source, "load-test/results/tenancy-summary.json");
  assert.equal(report.thresholds.approval_race_p99.target, 3000);
  assert.equal(report.checks.find((check) => check.id === "approval_race_p99").compare, "max");
  assert.deepEqual(
    report.regressions.map((regression) => regression.id),
    ["approval_race_p99"],
  );
});

test("renderSummaryMarkdown renders the table and the regression list", () => {
  const summary = healthySummary();
  summary.metrics["http_req_failed{scenario:tenant_isolation}"].values.rate = 0.5;
  const markdown = renderSummaryMarkdown(
    buildReport({ summary, mode: "smoke", tenants: 20, generatedAt: "2026-11-11T00:00:00.000Z" }),
  );
  assert.match(markdown, /1000\+ tenant load test \(smoke\)/);
  assert.match(markdown, /Tenants: \*\*20\*\*/);
  assert.match(markdown, /Tenant isolation error rate/);
  assert.match(markdown, /### Regressions/);
  assert.match(markdown, /tenant_isolation_error_rate/);
});

test("renderSummaryMarkdown tolerates a missing check list", () => {
  assert.match(renderSummaryMarkdown({ generatedAt: "now" }), /Result: \*\*unknown\*\*/);
});
