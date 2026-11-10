#!/usr/bin/env node
// Issue #196: publish the coverage trend and fail on regression.
//
// The committed baseline (docs/coverage/trend.json) records the current bundle and
// per-package coverage plus the active ratchet minimums. CI compares the freshly
// measured JaCoCo CSV against both; maintainers update the baseline with --write
// when a ratchet step is intentionally raised.

import { readFileSync, writeFileSync, appendFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { pathToFileURL } from "node:url";

const EXCLUSION_PATTERNS = [
  /\.interfaces\.dto$/,
  /\.config$/,
  /\.identity\.config$/,
  /IdentityApplication$/,
];

export function isExcludedPackage(packageName, className = "") {
  if (className === "IdentityApplication") {
    return true;
  }
  return EXCLUSION_PATTERNS.some((pattern) => pattern.test(packageName));
}

function ratio(covered, missed) {
  const total = covered + missed;
  return total === 0 ? 0 : covered / total;
}

/**
 * Aggregates a JaCoCo CSV report into bundle and per-package line/branch ratios.
 */
export function computeCoverage(csvText) {
  const lines = csvText.split(/\r?\n/).filter((line) => line.length > 0);
  if (lines.length < 2) {
    throw new Error("JaCoCo CSV has no data rows");
  }
  const header = lines[0].split(",");
  const index = Object.fromEntries(header.map((name, position) => [name, position]));
  for (const required of [
    "PACKAGE",
    "CLASS",
    "LINE_MISSED",
    "LINE_COVERED",
    "BRANCH_MISSED",
    "BRANCH_COVERED",
  ]) {
    if (index[required] === undefined) {
      throw new Error(`JaCoCo CSV is missing the ${required} column`);
    }
  }
  const bundle = { lineCovered: 0, lineMissed: 0, branchCovered: 0, branchMissed: 0 };
  const packages = new Map();
  for (const line of lines.slice(1)) {
    const columns = line.split(",");
    const packageName = columns[index.PACKAGE];
    const className = columns[index.CLASS];
    if (isExcludedPackage(packageName, className)) {
      continue;
    }
    const lineCovered = Number(columns[index.LINE_COVERED]);
    const lineMissed = Number(columns[index.LINE_MISSED]);
    const branchCovered = Number(columns[index.BRANCH_COVERED]);
    const branchMissed = Number(columns[index.BRANCH_MISSED]);
    bundle.lineCovered += lineCovered;
    bundle.lineMissed += lineMissed;
    bundle.branchCovered += branchCovered;
    bundle.branchMissed += branchMissed;
    const aggregate = packages.get(packageName) ?? {
      lineCovered: 0,
      lineMissed: 0,
      branchCovered: 0,
      branchMissed: 0,
    };
    aggregate.lineCovered += lineCovered;
    aggregate.lineMissed += lineMissed;
    aggregate.branchCovered += branchCovered;
    aggregate.branchMissed += branchMissed;
    packages.set(packageName, aggregate);
  }
  return {
    bundle: {
      line: ratio(bundle.lineCovered, bundle.lineMissed),
      branch: ratio(bundle.branchCovered, bundle.branchMissed),
    },
    packages: Object.fromEntries(
      [...packages.entries()]
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([name, aggregate]) => [
          name,
          {
            line: ratio(aggregate.lineCovered, aggregate.lineMissed),
            branch: ratio(aggregate.branchCovered, aggregate.branchMissed),
          },
        ]),
    ),
  };
}

/**
 * Compares the measured coverage against the committed baseline. A regression is
 * either dropping below the recorded value or below the active ratchet minimum.
 */
export function checkRegression(current, baseline, epsilon = 1e-9) {
  const failures = [];
  const minimum = baseline.minimum ?? {};
  if (current.bundle.line + epsilon < minimum.line) {
    failures.push(
      `bundle line ${(current.bundle.line * 100).toFixed(2)}% < minimum ${(minimum.line * 100).toFixed(2)}%`,
    );
  }
  if (current.bundle.branch + epsilon < minimum.branch) {
    failures.push(
      `bundle branch ${(current.bundle.branch * 100).toFixed(2)}% < minimum ${(minimum.branch * 100).toFixed(2)}%`,
    );
  }
  const recorded = baseline.bundle ?? {};
  if (current.bundle.line + epsilon < (recorded.line ?? 0)) {
    failures.push(
      `bundle line ${(current.bundle.line * 100).toFixed(2)}% regressed from baseline ${(recorded.line * 100).toFixed(2)}%`,
    );
  }
  if (current.bundle.branch + epsilon < (recorded.branch ?? 0)) {
    failures.push(
      `bundle branch ${(current.bundle.branch * 100).toFixed(2)}% regressed from baseline ${(recorded.branch * 100).toFixed(2)}%`,
    );
  }
  return { passed: failures.length === 0, failures };
}

export function buildSnapshot(current, previous = {}, date = new Date().toISOString().slice(0, 10)) {
  const minimum = previous.minimum ?? { line: 0.8, branch: 0.6 };
  return {
    updated: date,
    minimum: { line: minimum.line, branch: minimum.branch },
    bundle: { line: current.bundle.line, branch: current.bundle.branch },
    packages: current.packages,
  };
}

export function formatSummary(current, baseline, result, artifact = "coverage-trend.json") {
  const lines = [
    "### Coverage trend",
    "",
    "| Metric | Measured | Baseline | Minimum |",
    "| --- | --- | --- | --- |",
    `| Bundle line | ${(current.bundle.line * 100).toFixed(2)}% | ${((baseline.bundle?.line ?? 0) * 100).toFixed(2)}% | ${((baseline.minimum?.line ?? 0) * 100).toFixed(2)}% |`,
    `| Bundle branch | ${(current.bundle.branch * 100).toFixed(2)}% | ${((baseline.bundle?.branch ?? 0) * 100).toFixed(2)}% | ${((baseline.minimum?.branch ?? 0) * 100).toFixed(2)}% |`,
    "",
    `- Result: ${result.passed ? "pass" : "fail"}`,
    `- Current snapshot artifact: ${artifact}`,
    "- Coverage report artifact: jacoco-report",
  ];
  if (!result.passed) {
    lines.push("", "Failures:", ...result.failures.map((failure) => `- ${failure}`));
  }
  return `${lines.join("\n")}\n`;
}

function parseArgs(argv) {
  const options = {
    csv: null,
    baseline: "docs/coverage/trend.json",
    check: false,
    write: false,
    emit: null,
    summary: null,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--csv") {
      options.csv = argv[++i];
    } else if (arg === "--baseline") {
      options.baseline = argv[++i];
    } else if (arg === "--check") {
      options.check = true;
    } else if (arg === "--write") {
      options.write = true;
    } else if (arg === "--emit") {
      options.emit = argv[++i];
    } else if (arg === "--summary") {
      options.summary = argv[++i];
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  if (!options.csv) {
    throw new Error("--csv is required");
  }
  return options;
}

function readJson(path, fallback = null) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    if (fallback !== null && error.code === "ENOENT") {
      return fallback;
    }
    throw error;
  }
}

function writeJson(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const current = computeCoverage(readFileSync(options.csv, "utf8"));
  const baseline = readJson(options.baseline, {
    minimum: { line: 0.8, branch: 0.6 },
    bundle: { line: 0, branch: 0 },
    packages: {},
  });
  const result = checkRegression(current, baseline);
  const snapshot = buildSnapshot(current, baseline);
  if (options.write) {
    writeJson(options.baseline, snapshot);
  }
  if (options.emit) {
    writeJson(options.emit, snapshot);
  }
  const summary = formatSummary(
    current,
    baseline,
    result,
    options.emit ? options.emit : "coverage-trend.json",
  );
  process.stdout.write(summary);
  if (options.summary) {
    appendFileSync(options.summary, summary);
  }
  if (options.check && !result.passed) {
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
