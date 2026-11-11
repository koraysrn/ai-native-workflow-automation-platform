import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

const WORKFLOW = new URL("../../.github/workflows/load-test-nightly.yml", import.meta.url);
const SCRIPT = new URL("../k6/awap-tenancy-load-test.js", import.meta.url);
const SQL = new URL("../verify-isolation.sql", import.meta.url);
const README = new URL("../README.md", import.meta.url);
const METHODOLOGY = new URL("../../docs/benchmarks/tenant-load-methodology.md", import.meta.url);

function lineIndex(lines, pattern) {
  return lines.findIndex((line) => pattern.test(line));
}

test("the nightly job seeds the 1000+ tenant grid before the tenancy run", async () => {
  const lines = (await readFile(WORKFLOW, "utf8")).split("\n");

  const seed = lineIndex(lines, /node load-test\/seed-tenants\.mjs/);
  const run = lineIndex(lines, /k6 run load-test\/k6\/awap-tenancy-load-test\.js/);
  const report = lineIndex(lines, /node load-test\/report-slo\.mjs$/);

  assert.ok(seed >= 0, "the workflow must seed the tenancy grid");
  assert.ok(run >= 0, "the workflow must run the tenancy k6 script");
  assert.ok(report >= 0, "the workflow must evaluate the tenancy SLOs");
  assert.ok(seed < run, "seed the tenants before the tenancy run");
  assert.ok(run < report, "evaluate the report after the k6 run");
});

test("the tenant count defaults to the issue floor and is overridable", async () => {
  const workflow = await readFile(WORKFLOW, "utf8");
  assert.match(workflow, /TENANT_COUNT: \$\{\{ vars\.LOAD_TEST_TENANT_COUNT \|\| '1000' \}\}/);
  assert.match(workflow, /--tenants "\$TENANT_COUNT"/);
});

test("the static job executes both dry runs and bundles both k6 scripts", async () => {
  const workflow = await readFile(WORKFLOW, "utf8");
  assert.match(
    workflow,
    /node load-test\/seed-tenants\.mjs --tenants 1000 --concurrency 16 --dry-run/,
  );
  assert.match(workflow, /node load-test\/report-slo\.mjs --dry-run/);
  assert.match(workflow, /awap-tenancy-load-test\.js[\s\S]*?--bundle --external:k6/);
});

test("a failed tenancy run still publishes the report and keeps the job red", async () => {
  const workflow = await readFile(WORKFLOW, "utf8");
  assert.match(workflow, /name: Evaluate the tenancy SLOs\n\s+if: always\(\)/);
  assert.match(workflow, /--step-summary "\$GITHUB_STEP_SUMMARY"/);
  assert.match(
    workflow,
    /report-slo\.mjs[\s\S]*?--output load-test\/results\/tenancy-slo-report\.json/,
  );
});

test("the RLS assertion runs as the non-superuser runtime role", async () => {
  const workflow = await readFile(WORKFLOW, "utf8");
  const lines = workflow.split("\n");
  const step = lineIndex(lines, /name: Assert Postgres RLS hides other tenants/);
  assert.ok(step >= 0, "the workflow must assert RLS in Postgres");

  const block = lines.slice(step, step + 12).join("\n");
  assert.match(block, /psql -v ON_ERROR_STOP=1 -U awap_app/);
  assert.ok(!block.includes("-U awap "), "a superuser bypasses RLS and hides a breach");
  assert.match(block, /verify-isolation\.sql/);
  assert.match(block, /-v tenant_id="\$TENANT_A" -v foreign_id="\$TENANT_B"/);
});

test("the isolation SQL refuses to run as a superuser and raises on a leak", async () => {
  const sql = await readFile(SQL, "utf8");
  assert.match(sql, /rolsuper FROM pg_roles WHERE rolname = current_user/);
  assert.match(sql, /SET awap\.tenant_id = :'tenant_id'/);
  assert.match(sql, /RAISE EXCEPTION/);
  assert.match(sql, /workflow_instances/);
  assert.match(sql, /foreign_id/);
});

test("the k6 script shares one tenant list and asserts isolation on every iteration", async () => {
  const script = await readFile(SCRIPT, "utf8");
  assert.match(script, /new SharedArray\("tenants"/);
  assert.match(script, /__ENV\.TENANTS_FILE/);
  for (const exec of ["tenantIsolation", "approvalRace", "idempotentStart"]) {
    assert.match(script, new RegExp(`export function ${exec}\\(`));
  }
  assert.match(script, /isolation_violations/);
  assert.match(script, /cross_tenant_leaks/);
  assert.match(script, /approval_race_violations/);
  assert.match(script, /idempotency_violations/);
  assert.match(script, /http\.batch/);
  assert.match(script, /Idempotency-Key/);
  // A race legitimately answers 409; without expectedStatuses the error-rate SLO would punish it.
  assert.match(script, /http\.expectedStatuses\(\{ min: 200, max: 299 \}, 409\)/);
});

test("the load job does not upload the seeded credentials", async () => {
  const workflow = await readFile(WORKFLOW, "utf8");
  const artifactPath = workflow.slice(workflow.indexOf("path: |"));
  assert.ok(!artifactPath.includes("tenants.json"), "credentials must never be uploaded");
  assert.match(artifactPath, /load-test\/results/);
});

test("the methodology is linked from the load-test README", async () => {
  const readme = await readFile(README, "utf8");
  assert.match(readme, /docs\/benchmarks\/tenant-load-methodology\.md/);
  assert.match(readme, /node load-test\/seed-tenants\.mjs --tenants 1000 --dry-run/);
  assert.match(readme, /awap_app/);
  assert.match(await readFile(METHODOLOGY, "utf8"), /p99 < 4000 ms/);
});
