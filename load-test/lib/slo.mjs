/**
 * SLO catalogue and evaluation for the 1000+ tenant load test (issue #201).
 *
 * The same table drives the k6 thresholds (`k6/lib/tenancy-scenarios.mjs`) and the reporting tool
 * (`report-slo.mjs`), so a nightly run cannot silently lose a gate: the thresholds fail k6 and the
 * report fails the job with the exact metric that regressed.
 */

/** Latency/error budgets per scenario plus the zero-tolerance correctness counters. */
export const SLO_CHECKS = [
  {
    id: "tenant_isolation_p95",
    label: "Tenant isolation read p95",
    metric: "http_req_duration{scenario:tenant_isolation}",
    stat: "p(95)",
    target: 2000,
    unit: "ms",
    compare: "max",
  },
  {
    id: "tenant_isolation_p99",
    label: "Tenant isolation read p99",
    metric: "http_req_duration{scenario:tenant_isolation}",
    stat: "p(99)",
    target: 4000,
    unit: "ms",
    compare: "max",
  },
  {
    id: "tenant_isolation_error_rate",
    label: "Tenant isolation error rate",
    metric: "http_req_failed{scenario:tenant_isolation}",
    stat: "rate",
    target: 0.01,
    unit: "ratio",
    compare: "max",
  },
  {
    id: "approval_race_p95",
    label: "Approval race p95",
    metric: "http_req_duration{scenario:approval_race}",
    stat: "p(95)",
    target: 1500,
    unit: "ms",
    compare: "max",
  },
  {
    id: "approval_race_p99",
    label: "Approval race p99",
    metric: "http_req_duration{scenario:approval_race}",
    stat: "p(99)",
    target: 3000,
    unit: "ms",
    compare: "max",
  },
  {
    id: "approval_race_error_rate",
    label: "Approval race error rate",
    metric: "http_req_failed{scenario:approval_race}",
    stat: "rate",
    target: 0.01,
    unit: "ratio",
    compare: "max",
  },
  {
    id: "idempotent_start_p95",
    label: "Idempotent start p95",
    metric: "http_req_duration{scenario:idempotent_start}",
    stat: "p(95)",
    target: 2000,
    unit: "ms",
    compare: "max",
  },
  {
    id: "idempotent_start_p99",
    label: "Idempotent start p99",
    metric: "http_req_duration{scenario:idempotent_start}",
    stat: "p(99)",
    target: 4000,
    unit: "ms",
    compare: "max",
  },
  {
    id: "idempotent_start_error_rate",
    label: "Idempotent start error rate",
    metric: "http_req_failed{scenario:idempotent_start}",
    stat: "rate",
    target: 0.01,
    unit: "ratio",
    compare: "max",
  },
  {
    id: "isolation_violations",
    label: "Rows from another tenant in an own-tenant list",
    metric: "isolation_violations",
    stat: "count",
    target: 0,
    unit: "count",
    compare: "max",
  },
  {
    id: "cross_tenant_leaks",
    label: "Cross-tenant read answered 2xx",
    metric: "cross_tenant_leaks",
    stat: "count",
    target: 0,
    unit: "count",
    compare: "max",
  },
  {
    id: "approval_race_violations",
    label: "Approval races without exactly one winner",
    metric: "approval_race_violations",
    stat: "count",
    target: 0,
    unit: "count",
    compare: "max",
  },
  {
    id: "idempotency_violations",
    label: "Duplicate Idempotency-Key created a second instance",
    metric: "idempotency_violations",
    stat: "count",
    target: 0,
    unit: "count",
    compare: "max",
  },
];

/**
 * Throughput is a full-profile budget: the smoke profile intentionally runs three VUs with think
 * time, so gating it there would fail every smoke run for doing what it is designed to do.
 */
export const THROUGHPUT_CHECK = {
  id: "throughput",
  label: "Overall request rate",
  metric: "http_reqs",
  stat: "rate",
  target: 20,
  unit: "req/s",
  compare: "min",
};

/** The checks that apply to a profile. */
export function checksFor(mode) {
  return mode === "smoke" ? SLO_CHECKS : [...SLO_CHECKS, THROUGHPUT_CHECK];
}

/**
 * Zero-valued metrics can be absent from a k6 summary: a counter that was never incremented and an
 * `http_req_failed` sub-metric with no failures are not exported. Reading those as "not measured"
 * would fail a perfectly healthy run, so they default to 0. Latency metrics are always exported
 * when their scenario ran, so a missing latency stays a hard failure.
 */
export const CARRY_ZERO_WHEN_ABSENT = new Set([
  "isolation_violations|count",
  "cross_tenant_leaks|count",
  "approval_race_violations|count",
  "idempotency_violations|count",
  "http_req_failed{scenario:tenant_isolation}|rate",
  "http_req_failed{scenario:approval_race}|rate",
  "http_req_failed{scenario:idempotent_start}|rate",
]);

/**
 * Reads one stat from a k6 `--summary-export` document.
 *
 * `--summary-export` writes metric stats inline (`metrics[name]["p(95)"]`), while the end-of-test
 * summary nests them under `values`. Both shapes are accepted so the report works with either file.
 */
export function metricValue(summary, metric, stat) {
  const entry = summary?.metrics?.[metric];
  if (!entry || typeof entry !== "object") {
    return undefined;
  }
  const values =
    entry.values && typeof entry.values === "object" && !Array.isArray(entry.values)
      ? entry.values
      : entry;
  const value = values[stat];
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

/** Evaluates every SLO and returns the full check list plus the failing subset. */
export function evaluateSlo(summary, checks = SLO_CHECKS) {
  const results = checks.map((check) => {
    const observed = metricValue(summary, check.metric, check.stat);
    const absentCountsAsZero =
      observed === undefined && CARRY_ZERO_WHEN_ABSENT.has(`${check.metric}|${check.stat}`);
    const actual = absentCountsAsZero ? 0 : observed;
    if (actual === undefined) {
      return {
        id: check.id,
        label: check.label,
        metric: check.metric,
        stat: check.stat,
        target: check.target,
        unit: check.unit,
        actual: null,
        observed: false,
        pass: false,
        reason: "metric-missing",
      };
    }
    const pass = check.compare === "min" ? actual >= check.target : actual <= check.target;
    return {
      id: check.id,
      label: check.label,
      metric: check.metric,
      stat: check.stat,
      target: check.target,
      unit: check.unit,
      actual,
      observed: observed !== undefined,
      pass,
      reason: pass ? "within-budget" : "budget-exceeded",
    };
  });

  return {
    pass: results.every((result) => result.pass),
    checks: results,
    regressions: results.filter((result) => !result.pass),
  };
}

/** Human-readable markdown for the GitHub job summary and the docs. */
export function renderSummaryMarkdown(report) {
  const lines = [
    `## 1000+ tenant load test (${report.mode})`,
    "",
    `- Tenants: **${report.target?.tenants ?? "unknown"}**`,
    `- Result: **${report.result ?? "unknown"}**`,
    `- Generated: ${report.generatedAt}`,
    "",
    "| SLO | Target | Actual | Verdict |",
    "|---|---|---|---|",
  ];
  for (const check of report.checks ?? []) {
    const actual = check.actual === null ? "not measured" : String(check.actual);
    lines.push(
      `| ${check.label} | ${check.compare ?? ""} ${check.target} ${check.unit} | ${actual} | ${
        check.pass ? "pass" : "fail"
      } |`,
    );
  }
  if ((report.regressions ?? []).length > 0) {
    lines.push("", "### Regressions", "");
    for (const regression of report.regressions) {
      lines.push(`- \`${regression.id}\`: ${regression.reason}`);
    }
  }
  return `${lines.join("\n")}\n`;
}

/** Assembles the committed/report artifact. */
export function buildReport({
  summary,
  mode = "full",
  tenants = 0,
  apiBase = "",
  source = "",
  generatedAt = new Date().toISOString(),
  checks = checksFor(mode),
}) {
  const evaluation = evaluateSlo(summary, checks);
  return {
    schemaVersion: 1,
    issue: 201,
    generatedAt,
    mode,
    target: { tenants, profile: "tenancy-load" },
    environment: { apiBase, runner: process.env.RUNNER_NAME ?? "local" },
    source,
    thresholds: Object.fromEntries(
      checks.map((check) => [
        check.id,
        {
          metric: check.metric,
          stat: check.stat,
          target: check.target,
          compare: check.compare,
          unit: check.unit,
        },
      ]),
    ),
    checks: evaluation.checks.map((check) => ({
      ...check,
      compare: checks.find((entry) => entry.id === check.id)?.compare ?? "max",
    })),
    regressions: evaluation.regressions,
    result: evaluation.pass ? "pass" : "fail",
  };
}

/**
 * A representative summary used by `--dry-run` in CI. The numbers are deliberately healthy so the
 * dry run proves the plumbing (parse -> evaluate -> render) without pretending to be a measurement;
 * the report labels the mode `dry-run` and the artifact never lands in `docs/benchmarks`.
 */
export const DRY_RUN_SUMMARY = {
  metrics: {
    "http_req_duration{scenario:tenant_isolation}": { values: { "p(95)": 120, "p(99)": 240 } },
    "http_req_failed{scenario:tenant_isolation}": { values: { rate: 0 } },
    "http_req_duration{scenario:approval_race}": { values: { "p(95)": 90, "p(99)": 180 } },
    "http_req_failed{scenario:approval_race}": { values: { rate: 0 } },
    "http_req_duration{scenario:idempotent_start}": { values: { "p(95)": 110, "p(99)": 220 } },
    "http_req_failed{scenario:idempotent_start}": { values: { rate: 0 } },
    http_reqs: { values: { rate: 250 } },
  },
};
