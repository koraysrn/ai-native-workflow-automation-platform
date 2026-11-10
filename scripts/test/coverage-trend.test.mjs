import assert from "node:assert/strict";
import test from "node:test";

import {
  buildSnapshot,
  checkRegression,
  computeCoverage,
  isExcludedPackage,
} from "../coverage-trend.mjs";

const CSV = [
  "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED",
  "AWAP,demo,DemoService,0,10,0,10,0,10",
  "AWAP,com.awap.organization.interfaces.dto,PositionResponse,0,1,0,0,0,1",
  "AWAP,com.awap.identity,IdentityApplication,5,0,0,0,5,0",
].join("\n");

test("computeCoverage aggregates the bundle and per-package ratios", () => {
  const coverage = computeCoverage(CSV);
  assert.equal(coverage.bundle.line, 1);
  assert.equal(coverage.bundle.branch, 1);
  assert.deepEqual(coverage.packages.demo, { line: 1, branch: 1 });
});

test("DTO and application bootstrap classes are excluded", () => {
  assert.equal(isExcludedPackage("com.awap.organization.interfaces.dto"), true);
  assert.equal(isExcludedPackage("com.awap.identity", "IdentityApplication"), true);
  assert.equal(isExcludedPackage("com.awap.organization.application"), false);
  const coverage = computeCoverage(CSV);
  assert.equal(coverage.packages["com.awap.organization.interfaces.dto"], undefined);
  assert.equal(coverage.packages["com.awap.identity"], undefined);
});

test("meeting both the minimum and the recorded baseline passes", () => {
  const baseline = {
    minimum: { line: 0.8, branch: 0.6 },
    bundle: { line: 0.85, branch: 0.7 },
  };
  const result = checkRegression(
    { bundle: { line: 0.85, branch: 0.7 }, packages: {} },
    baseline,
  );
  assert.equal(result.passed, true);
  assert.deepEqual(result.failures, []);
});

test("dropping below the ratchet minimum fails", () => {
  const baseline = {
    minimum: { line: 0.8, branch: 0.6 },
    bundle: { line: 0.85, branch: 0.7 },
  };
  const result = checkRegression(
    { bundle: { line: 0.79, branch: 0.65 }, packages: {} },
    baseline,
  );
  assert.equal(result.passed, false);
  assert.ok(result.failures.some((failure) => failure.includes("minimum 80.00%")));
});

test("a regression from the recorded baseline fails even above the minimum", () => {
  const baseline = {
    minimum: { line: 0.8, branch: 0.6 },
    bundle: { line: 0.9, branch: 0.75 },
  };
  const result = checkRegression(
    { bundle: { line: 0.87, branch: 0.72 }, packages: {} },
    baseline,
  );
  assert.equal(result.passed, false);
  assert.ok(result.failures.some((failure) => failure.includes("regressed from baseline")));
});

test("buildSnapshot preserves the active minimum and records the measurement", () => {
  const snapshot = buildSnapshot(
    { bundle: { line: 0.86, branch: 0.72 }, packages: { demo: { line: 1, branch: 1 } } },
    { minimum: { line: 0.8, branch: 0.6 } },
    "2026-11-10",
  );
  assert.deepEqual(snapshot.minimum, { line: 0.8, branch: 0.6 });
  assert.equal(snapshot.bundle.line, 0.86);
  assert.equal(snapshot.updated, "2026-11-10");
});
