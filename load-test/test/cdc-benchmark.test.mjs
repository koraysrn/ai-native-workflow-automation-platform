import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { test } from "node:test";
import {
  buildBenchmarkReport,
  computeCdcLags,
  isCdcRegistered,
  normalizeSamples,
  parseConsoleConsumerRecords,
  parseOutboxRows,
  parsePsqlSamples,
  summarize,
} from "../lib/cdc-benchmark.mjs";

const execFileAsync = promisify(execFile);
const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const EVENT_ID = "11111111-1111-1111-1111-111111111111";
const AGGREGATE_ID = "22222222-2222-2222-2222-222222222222";
const TENANT_ID = "33333333-3333-3333-3333-333333333333";

test("normalizeSamples accepts arrays and sample documents and drops non-finite entries", () => {
  assert.deepEqual(normalizeSamples([1, "2", Number.NaN, null]), [1, 2, 0]);
  assert.deepEqual(normalizeSamples({ samples: [3, 4] }, "polling"), [3, 4]);
  assert.throws(() => normalizeSamples({ samples: [] }, "polling"), /non-empty/);
  assert.throws(() => normalizeSamples(["x"], "polling"), /finite numeric/);
});

test("summarize reports count, nearest-rank p50/p95 and max", () => {
  assert.deepEqual(summarize("polling", [30, 10, 20]), {
    label: "polling",
    count: 3,
    p50: 20,
    p95: 30,
    max: 30,
    samples: [30, 10, 20],
  });
});

test("CDC registration is independent from having samples yet", () => {
  assert.equal(isCdcRegistered({ registered: false, samples: [] }), false);
  assert.equal(isCdcRegistered({ registered: true, samples: [] }), true);
  assert.equal(isCdcRegistered({ samples: [12] }), true);
  assert.equal(isCdcRegistered([12]), true);
});

test("polling-only report keeps CDC optional and still evaluates the verdict", () => {
  const report = buildBenchmarkReport({
    polling: { samples: [100, 200, 300] },
    cdc: { registered: false, reason: "connector missing", samples: [] },
    generatedAt: "2026-11-02T00:00:00.000Z",
  });

  assert.equal(report.polling.count, 3);
  assert.equal(report.polling.p50, 200);
  assert.equal(report.polling.p95, 300);
  assert.equal(report.polling.max, 300);
  assert.equal(report.cdc.registered, false);
  assert.equal(report.cdc.count, 0);
  assert.equal(report.cdc.p50, null);
  assert.equal(report.cdc.reason, "connector missing");
  assert.equal(report.improvementP95Ms, null);
  assert.deepEqual(report.verdict, { cutover: false, reasons: [] });
});

test("polling vs CDC report prints both summaries and the improvement", () => {
  const report = buildBenchmarkReport({
    polling: { samples: [1_000, 2_000, 3_000] },
    cdc: { registered: true, connectorState: "RUNNING", samples: [100, 200, 300] },
    generatedAt: "2026-11-02T00:00:00.000Z",
  });

  assert.deepEqual(
    {
      count: report.cdc.count,
      p50: report.cdc.p50,
      p95: report.cdc.p95,
      max: report.cdc.max,
    },
    { count: 3, p50: 200, p95: 300, max: 300 },
  );
  assert.equal(report.cdc.connectorState, "RUNNING");
  assert.equal(report.improvementP95Ms, 2_700);
});

test("a registered connector without records is represented, not treated as missing", () => {
  const report = buildBenchmarkReport({
    polling: { samples: [10] },
    cdc: { registered: true, connectorState: "RUNNING", samples: [] },
  });
  assert.equal(report.cdc.registered, true);
  assert.equal(report.cdc.count, 0);
  assert.equal(report.cdc.max, null);
});

test("cutover inputs from the environment are part of the verdict", () => {
  const report = buildBenchmarkReport({
    polling: { samples: [800] },
    cutoverInputs: { slotLagBytes: 600 * 1024 * 1024 },
  });
  assert.equal(report.verdict.cutover, true);
  assert.match(report.verdict.reasons[0], /replication slot lag/);
});

test("parsePsqlSamples reads one finite millisecond sample per line", () => {
  assert.deepEqual(parsePsqlSamples(" 12\n0\n\n-3.4\nnot-a-number\n"), [12, 0, 0]);
});

test("parseOutboxRows keeps id/created_at pairs and skips malformed rows", () => {
  const output = `${EVENT_ID}\t1762100000000\nnot-a-uuid\t1762100000000\n`;
  assert.deepEqual(parseOutboxRows(output), [
    { id: EVENT_ID, createdAtMs: 1762100000000 },
  ]);
});

test("parseConsoleConsumerRecords reads the EventRouter id header and Kafka timestamp", () => {
  const stdout = [
    `CreateTime:1762100000123\tid:${EVENT_ID},tenantId:${TENANT_ID}\t${AGGREGATE_ID}\t{"state":"STARTED","id":"99999999-9999-9999-9999-999999999999"}`,
    `id:44444444-4444-4444-4444-444444444444,tenantId:${TENANT_ID}\tCreateTime:1762100000456\t${AGGREGATE_ID}\t{}`,
    "Processed a total of 2 messages",
  ].join("\n");

  assert.deepEqual(parseConsoleConsumerRecords(stdout), [
    { eventId: EVENT_ID, publishedAtMs: 1762100000123 },
    { eventId: "44444444-4444-4444-4444-444444444444", publishedAtMs: 1762100000456 },
  ]);
});

test("computeCdcLags joins by event id and reports duplicates/unmatched rows", () => {
  const rows = [
    { id: EVENT_ID, createdAtMs: 1762100000000 },
    { id: "55555555-5555-5555-5555-555555555555", createdAtMs: 1762100000000 },
  ];
  const records = [
    { eventId: EVENT_ID, publishedAtMs: 1762100000250 },
    { eventId: EVENT_ID, publishedAtMs: 1762100000999 },
    { eventId: "66666666-6666-6666-6666-666666666666", publishedAtMs: 1762100000300 },
  ];

  const lags = computeCdcLags(rows, records);
  assert.deepEqual(lags.samples, [250]);
  assert.equal(lags.matched, 1);
  assert.equal(lags.unmatched, 1);
  assert.equal(lags.duplicateRecords, 1);
  assert.equal(lags.unmatchedRecords, 1);
});

test("computeCdcLags clamps small clock skew instead of emitting negative latency", () => {
  const lags = computeCdcLags(
    [{ id: EVENT_ID, createdAtMs: 1762100000100 }],
    [{ eventId: EVENT_ID, publishedAtMs: 1762100000000 }],
  );
  assert.deepEqual(lags.samples, [0]);
});

test("benchmark CLI prints polling-only results and exits zero without CDC", async () => {
  const directory = await mkdtemp(join(tmpdir(), "cdc-benchmark-"));
  const pollingPath = join(directory, "polling.json");
  const cdcPath = join(directory, "cdc.json");
  const outputPath = join(directory, "report.json");
  await writeFile(pollingPath, JSON.stringify({ samples: [10, 20, 30] }));
  await writeFile(cdcPath, JSON.stringify({ registered: false, samples: [] }));

  const { stdout } = await execFileAsync(process.execPath, [
    "load-test/cdc-benchmark.mjs",
    pollingPath,
    cdcPath,
    "--output",
    outputPath,
  ], { cwd: ROOT });

  const report = JSON.parse(stdout);
  assert.equal(report.polling.p50, 20);
  assert.equal(report.polling.p95, 30);
  assert.equal(report.polling.max, 30);
  assert.equal(report.cdc.registered, false);
  assert.equal(report.verdict.cutover, false);
  assert.deepEqual(JSON.parse(await readFile(outputPath, "utf8")), report);
});

test("benchmark CLI --require-cdc fails when the connector produced no samples", async () => {
  const directory = await mkdtemp(join(tmpdir(), "cdc-benchmark-"));
  const pollingPath = join(directory, "polling.json");
  const cdcPath = join(directory, "cdc.json");
  await writeFile(pollingPath, JSON.stringify({ samples: [10] }));
  await writeFile(cdcPath, JSON.stringify({ registered: true, samples: [] }));

  await assert.rejects(
    execFileAsync(process.execPath, [
      "load-test/cdc-benchmark.mjs",
      pollingPath,
      cdcPath,
      "--require-cdc",
    ], { cwd: ROOT }),
    (error) => error.code === 2 && /CDC was required/.test(error.stderr),
  );
});

test("benchmark CLI --fail-on-cutover fails when polling stays inside the budget", async () => {
  const directory = await mkdtemp(join(tmpdir(), "cdc-benchmark-"));
  const pollingPath = join(directory, "polling.json");
  await writeFile(pollingPath, JSON.stringify({ samples: [10] }));

  await assert.rejects(
    execFileAsync(process.execPath, [
      "load-test/cdc-benchmark.mjs",
      pollingPath,
      "--fail-on-cutover",
    ], { cwd: ROOT }),
    (error) => error.code === 1 && /no trigger fired/.test(error.stderr),
  );
});
