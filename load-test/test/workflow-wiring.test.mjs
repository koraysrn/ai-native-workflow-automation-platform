import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

const WORKFLOW = new URL("../../.github/workflows/load-test-nightly.yml", import.meta.url);
const EXPLAIN_SQL = new URL("../k6/explain-outbox-partition.sql", import.meta.url);

/** Index of the first line matching the pattern, or -1. */
function lineIndex(lines, pattern) {
  return lines.findIndex((line) => pattern.test(line));
}

test("the load-test job creates the gitignored results directory before k6 writes to it", async () => {
  const lines = (await readFile(WORKFLOW, "utf8")).split("\n");

  // load-test/results is gitignored, so a fresh checkout has no such directory: k6 logs an error,
  // drops the summary and the artifact upload then has nothing to attach.
  const mkdir = lineIndex(lines, /mkdir -p load-test\/results/);
  const summaryExport = lineIndex(lines, /--summary-export=load-test\/results\//);

  assert.ok(mkdir >= 0, "workflow must create load-test/results");
  assert.ok(summaryExport >= 0, "workflow must export the k6 summary into load-test/results");
  assert.ok(mkdir < summaryExport, "create load-test/results before k6 exports its summary");
});

test("k6 runs on the runner so localhost reaches the Compose stack", async () => {
  const workflow = await readFile(WORKFLOW, "utf8");

  // The Docker-based k6-action cannot reach a stack published on the runner's localhost and does
  // not inherit the variables written to $GITHUB_ENV by seed.mjs.
  assert.ok(!workflow.includes("grafana/k6-action"), "run k6 on the runner, not in a container");
  assert.match(workflow, /uses: grafana\/setup-k6-action@v1/);
  assert.match(workflow, /k6 run load-test\/k6\/awap-load-test\.js/);
  assert.match(workflow, /API_BASE: http:\/\/localhost:8080/);
  assert.match(workflow, /WEBHOOK_TARGET: http:\/\/localhost:9099\/webhook/);
});

test("seeded credentials reach k6 through the process environment, not the env context", async () => {
  const workflow = await readFile(WORKFLOW, "utf8");

  // seed.mjs writes these names to $GITHUB_ENV; the runner exports them into later steps. The
  // `env` expression context only resolves values declared in the workflow, so re-declaring them
  // as ${{ env.TENANT_ID }} passed empty strings and every request ran unauthenticated.
  for (const name of ["TENANT_ID", "ACCESS_TOKEN", "WORKFLOW_DEFINITION_ID"]) {
    assert.ok(
      !workflow.includes(`\${{ env.${name} }}`),
      `${name} must be inherited from \$GITHUB_ENV instead of \${{ env.${name} }}`,
    );
  }
});

test("the seeded credentials are verified before the k6 run", async () => {
  const lines = (await readFile(WORKFLOW, "utf8")).split("\n");

  const verify = lineIndex(lines, /name: Verify the seeded k6 variables are exported/);
  const run = lineIndex(lines, /name: Full run \(nightly and manual\)/);

  assert.ok(verify >= 0, "workflow must assert the seeded variables reached the environment");
  assert.ok(verify < run, "verify the seeded credentials before starting the k6 run");
});

test("the partition check fails on a plan regression instead of grepping any scan", async () => {
  const workflow = await readFile(WORKFLOW, "utf8");
  const sql = await readFile(EXPLAIN_SQL, "utf8");

  assert.match(workflow, /psql -v ON_ERROR_STOP=1/);
  assert.ok(!workflow.includes("grep -q 'Scan'"), "a bare Scan grep accepts any plan");
  // The pre-fix query selected a column that does not exist, and psql exited 0 anyway.
  assert.ok(!/\bpayload\b[^_]/.test(sql), "outbox_events has payload_json, not payload");
  assert.match(sql, /outbox_events_default/);
  assert.match(sql, /RAISE EXCEPTION/);
});
