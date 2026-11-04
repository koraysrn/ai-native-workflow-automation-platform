import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

const CI = new URL("../../.github/workflows/ci.yml", import.meta.url);
const ADR = new URL("../../docs/adr/0005-jackson-bom-override.md", import.meta.url);
const RUNBOOK = new URL("../../docs/operations-runbook.md", import.meta.url);
const REPORT_SCRIPT = new URL("../trivy-regression-report.mjs", import.meta.url);
const NOTIFY_SCRIPT = new URL("../trivy-regression-notify.mjs", import.meta.url);

test("the Trivy job emits the summary, artifact and gate", async () => {
  const workflow = await readFile(CI, "utf8");
  assert.match(workflow, /^ {2}trivy:\n {4}name: Trivy vulnerability scan$/m);
  assert.match(workflow, /format: json/);
  assert.match(workflow, /output: trivy-results\.json/);
  assert.match(workflow, /node scripts\/trivy-regression-report\.mjs/);
  assert.match(workflow, /--fail-on CRITICAL,HIGH/);
  assert.match(workflow, /name: trivy-report/);
  assert.match(workflow, /path: trivy-results\.json/);
});

test("the repeated-failure notification is scoped to main and has API permissions", async () => {
  const workflow = await readFile(CI, "utf8");
  assert.match(workflow, /issues: write/);
  assert.match(workflow, /actions: read/);
  assert.match(
    workflow,
    /if: failure\(\) && github\.ref == 'refs\/heads\/main'[\s\S]*?--mode failure[\s\S]*?--streak-threshold 2/,
  );
  assert.match(workflow, /if: success\(\) && github\.ref == 'refs\/heads\/main'/);
  assert.match(workflow, /--mode success/);
});

test("the regression tooling tests run in CI", async () => {
  const workflow = await readFile(CI, "utf8");
  assert.match(workflow, /node --test "scripts\/test\/\*\.test\.mjs"/);
});

test("ADR-0005 records the CI-only decision instead of a dashboard", async () => {
  const adr = await readFile(ADR, "utf8");
  assert.match(adr, /CI-only Trivy regression signal/);
  assert.match(adr, /no Pushgateway and no Prometheus scrape/);
  assert.match(adr, /two consecutive failing `main` runs/);
  assert.match(adr, /scripts\/trivy-regression-report\.mjs/);
  assert.match(adr, /scripts\/trivy-regression-notify\.mjs/);
});

test("the runbook O21 section explains triage and the Jackson override floor", async () => {
  const runbook = await readFile(RUNBOOK, "utf8");
  assert.match(runbook, /^## O21 — Trivy regression signal \(issue #162\)$/m);
  assert.match(runbook, /backend\/scripts\/check-jackson-versions\.sh/);
  assert.match(runbook, /2\.21\.7/);
  assert.match(runbook, /3\.1\.7/);
  assert.match(runbook, /\.trivyignore/);
  assert.match(runbook, /repeated-failure issue is the on-call signal/);
});

test("both implementation scripts exist and export their tested entry points", async () => {
  const report = await readFile(REPORT_SCRIPT, "utf8");
  const notify = await readFile(NOTIFY_SCRIPT, "utf8");
  assert.match(report, /export function findingsFromReport/);
  assert.match(report, /export function renderSummary/);
  assert.match(report, /export function renderAnnotations/);
  assert.match(notify, /export async function countConsecutiveFailures/);
  assert.match(notify, /export async function notifyFailure/);
  assert.match(notify, /export async function notifySuccess/);
});
