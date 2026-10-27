// Compares measured outbox publish lag for the polling relay and the Debezium connector and prints
// the cutover verdict using the same rules as the runbook (issue #131).
//
// Usage:
//   node load-test/cdc-benchmark.mjs polling.json cdc.json
//   node load-test/cdc-benchmark.mjs polling.json cdc.json --fail-on-cutover
//
// Each file is {"samples": [lagMs, ...]} (one entry per published event).
import { readFile } from "node:fs/promises";
import { CDC_TRIGGERS, evaluateCutover, percentile } from "./lib/cdc-triggers.mjs";

async function loadSamples(path) {
  const parsed = JSON.parse(await readFile(path, "utf8"));
  const samples = Array.isArray(parsed) ? parsed : parsed.samples;
  if (!Array.isArray(samples) || samples.length === 0) {
    throw new Error(`${path} must contain a non-empty "samples" array`);
  }
  return samples.map(Number).filter((value) => Number.isFinite(value));
}

function summarize(label, samples) {
  return {
    label,
    count: samples.length,
    p50: percentile(samples, 50),
    p95: percentile(samples, 95),
    max: Math.max(...samples),
  };
}

const [pollingPath, cdcPath] = process.argv.slice(2).filter((arg) => !arg.startsWith("--"));
if (!pollingPath || !cdcPath) {
  console.error("usage: node load-test/cdc-benchmark.mjs <polling.json> <cdc.json> [--fail-on-cutover]");
  process.exit(2);
}

const polling = summarize("polling", await loadSamples(pollingPath));
const cdc = summarize("cdc", await loadSamples(cdcPath));
const verdict = evaluateCutover({
  publishLagP95Ms: polling.p95,
  consecutiveBacklogSweeps: Number.parseInt(process.env.BACKLOG_SWEEPS ?? "0", 10),
  slotLagBytes: Number.parseInt(process.env.SLOT_LAG_BYTES ?? "0", 10),
  walGrowthBytesPerMin: Number.parseInt(process.env.WAL_GROWTH_BYTES_PER_MIN ?? "0", 10),
});

console.log(
  JSON.stringify(
    {
      thresholds: CDC_TRIGGERS,
      polling,
      cdc,
      improvementP95Ms: polling.p95 - cdc.p95,
      verdict,
    },
    null,
    2,
  ),
);

if (process.argv.includes("--fail-on-cutover") && !verdict.cutover) {
  console.error("Expected the polling measurements to justify a CDC cutover, but no trigger fired.");
  process.exit(1);
}
