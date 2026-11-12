import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
  idempotencyKey,
  evaluateRecord,
  formatSummary,
  main,
  parseArgs,
  parseExport,
  replay,
} from "../notification-dlq-replay.mjs";

const DELIVERY_ID = "11111111-1111-1111-1111-111111111111";

function workspace() {
  return mkdtempSync(path.join(tmpdir(), "awap-dlq-replay-"));
}

function dlqRecord(overrides = {}) {
  return {
    topic: "notification.send.dlq",
    partition: 0,
    offset: 7,
    key: DELIVERY_ID,
    value: JSON.stringify({
      deliveryId: DELIVERY_ID,
      dedupeKey: "k-1",
      channel: "EMAIL",
      attempts: 5,
      reason: "EXHAUSTED",
    }),
    ...overrides,
  };
}

function writeExport(dir, records) {
  const exportPath = path.join(dir, "dlq.jsonl");
  writeFileSync(exportPath, records.map((record) => `${JSON.stringify(record)}\n`).join(""));
  return exportPath;
}

function writeRows(dir, rows) {
  const rowsPath = path.join(dir, "rows.json");
  writeFileSync(rowsPath, JSON.stringify(rows));
  return rowsPath;
}

const FAILED_ROW = { id: DELIVERY_ID, status: "FAILED", providerMessageId: null };

test("parseArgs requires the export, the delivery rows and the output", () => {
  assert.throws(() => parseArgs(["--export", "a.jsonl"]), /Missing required argument/);
  assert.throws(() => parseArgs(["--nope"]), /Unknown argument/);
});

test("parseArgs derives the ledger and quarantine paths", () => {
  const options = parseArgs([
    "--export",
    "dlq.jsonl",
    "--delivery-rows",
    "rows.json",
    "--out",
    "replay.jsonl",
  ]);
  assert.equal(options.ledgerPath, "replay.jsonl.ledger.jsonl");
  assert.equal(options.quarantinePath, "replay.jsonl.quarantine.jsonl");
  assert.equal(options.dryRun, false);
});

test("parseArgs honours explicit paths and the dry-run flag", () => {
  const options = parseArgs([
    "--export",
    "dlq.jsonl",
    "--delivery-rows",
    "rows.json",
    "--out",
    "replay.jsonl",
    "--ledger",
    "ledger.jsonl",
    "--quarantine",
    "poison.jsonl",
    "--dry-run",
  ]);
  assert.equal(options.ledgerPath, "ledger.jsonl");
  assert.equal(options.quarantinePath, "poison.jsonl");
  assert.equal(options.dryRun, true);
});

test("parseExport skips blank lines and keeps unparsable lines for quarantine", () => {
  const entries = parseExport(`${JSON.stringify(dlqRecord())}\n\n{broken\n`);
  assert.equal(entries.length, 2);
  assert.equal(entries[0].record.topic, "notification.send.dlq");
  assert.equal(entries[1].record, null);
});

test("idempotencyKey is stable and changes with the dedupe key", () => {
  assert.equal(idempotencyKey(DELIVERY_ID, "k-1"), idempotencyKey(DELIVERY_ID, "k-1"));
  assert.notEqual(idempotencyKey(DELIVERY_ID, "k-1"), idempotencyKey(DELIVERY_ID, "k-2"));
});

test("a failed delivery with no provider id is replayable", () => {
  const outcome = evaluateRecord({ record: dlqRecord() }, [FAILED_ROW]);
  assert.equal(outcome.status, "replayable");
  assert.equal(outcome.deliveryId, DELIVERY_ID);
  assert.equal(outcome.reason, "EXHAUSTED");
});

test("a non-notification topic is skipped", () => {
  const outcome = evaluateRecord({ record: dlqRecord({ topic: "workflow.task.events.dlq" }) }, []);
  assert.equal(outcome.status, "skipped");
  assert.equal(outcome.reason, "not-a-notification-dlq");
});

test("a malformed payload is quarantined", () => {
  const outcome = evaluateRecord({ record: dlqRecord({ value: "{broken" }) }, []);
  assert.equal(outcome.status, "quarantined");
  assert.equal(outcome.reason, "invalid-json");
});

test("a non-object payload is quarantined", () => {
  const outcome = evaluateRecord({ record: dlqRecord({ value: "[1,2]" }) }, []);
  assert.equal(outcome.status, "quarantined");
  assert.equal(outcome.reason, "invalid-json");
});

test("a record without a delivery id is quarantined", () => {
  const outcome = evaluateRecord(
    { record: dlqRecord({ key: null, value: JSON.stringify({ dedupeKey: "k" }) }) },
    [],
  );
  assert.equal(outcome.status, "quarantined");
  assert.equal(outcome.reason, "missing-delivery-id");
});

test("an export line that is not an object is quarantined", () => {
  const outcome = evaluateRecord({ record: null }, []);
  assert.equal(outcome.status, "quarantined");
  assert.equal(outcome.reason, "invalid-export-line");
});

test("the record key is used when the payload omits the delivery id", () => {
  const outcome = evaluateRecord(
    { record: dlqRecord({ value: JSON.stringify({ dedupeKey: "k" }) }) },
    [FAILED_ROW],
  );
  assert.equal(outcome.status, "replayable");
  assert.equal(outcome.deliveryId, DELIVERY_ID);
});

test("a sent delivery is suppressed", () => {
  const outcome = evaluateRecord({ record: dlqRecord() }, [{ ...FAILED_ROW, status: "SENT" }]);
  assert.equal(outcome.reason, "already-sent");
});

test("a delivery with a provider message id is suppressed even when it looks failed", () => {
  const outcome = evaluateRecord({ record: dlqRecord() }, [
    { ...FAILED_ROW, providerMessageId: "<abc@awap.test>" },
  ]);
  assert.equal(outcome.reason, "already-sent");
});

test("a missing delivery row suppresses the replay", () => {
  const outcome = evaluateRecord({ record: dlqRecord() }, []);
  assert.equal(outcome.reason, "delivery-row-missing");
});

test("an unexpected delivery status suppresses the replay", () => {
  const outcome = evaluateRecord({ record: dlqRecord() }, [{ ...FAILED_ROW, status: "CANCELLED" }]);
  assert.equal(outcome.reason, "unexpected-status");
});

test("dry-run reports the plan and writes nothing", () => {
  const dir = workspace();
  const exportPath = writeExport(dir, [dlqRecord()]);
  const rowsPath = writeRows(dir, [FAILED_ROW]);
  const outPath = path.join(dir, "replay.jsonl");

  const summary = replay({
    exportPath,
    deliveryRowsPath: rowsPath,
    outPath,
    ledgerPath: `${outPath}.ledger.jsonl`,
    quarantinePath: `${outPath}.quarantine.jsonl`,
    dryRun: true,
  });

  assert.equal(summary.scanned, 1);
  assert.equal(summary.replayed, 1);
  assert.equal(summary.dryRun, true);
  assert.equal(existsSync(outPath), false);
  assert.equal(existsSync(`${outPath}.ledger.jsonl`), false);
});

test("a replay writes exactly one command and one ledger entry", () => {
  const dir = workspace();
  const exportPath = writeExport(dir, [dlqRecord()]);
  const rowsPath = writeRows(dir, [FAILED_ROW]);
  const outPath = path.join(dir, "replay.jsonl");

  const summary = replay({
    exportPath,
    deliveryRowsPath: rowsPath,
    outPath,
    ledgerPath: `${outPath}.ledger.jsonl`,
    quarantinePath: `${outPath}.quarantine.jsonl`,
    dryRun: false,
  });

  assert.equal(summary.replayed, 1);
  const commands = readFileSync(outPath, "utf8").trim().split("\n");
  assert.equal(commands.length, 1);
  const command = JSON.parse(commands[0]);
  assert.equal(command.topic, "notification.send");
  assert.equal(command.key, DELIVERY_ID);
  assert.equal(command.payload.replay, true);
  assert.equal(command.payload.originalReason, "EXHAUSTED");

  const ledger = readFileSync(`${outPath}.ledger.jsonl`, "utf8").trim().split("\n");
  assert.equal(ledger.length, 1);
  assert.equal(JSON.parse(ledger[0]).deliveryId, DELIVERY_ID);
});

test("a second run suppresses the already replayed record", () => {
  const dir = workspace();
  const exportPath = writeExport(dir, [dlqRecord()]);
  const rowsPath = writeRows(dir, [FAILED_ROW]);
  const outPath = path.join(dir, "replay.jsonl");
  const options = {
    exportPath,
    deliveryRowsPath: rowsPath,
    outPath,
    ledgerPath: `${outPath}.ledger.jsonl`,
    quarantinePath: `${outPath}.quarantine.jsonl`,
    dryRun: false,
  };

  replay(options);
  const second = replay(options);

  assert.equal(second.replayed, 0);
  assert.deepEqual(second.skipped, [{ deliveryId: DELIVERY_ID, reason: "duplicate-replay" }]);
  assert.equal(readFileSync(outPath, "utf8").trim().split("\n").length, 1);
});

test("a corrupt ledger line does not stop the replay", () => {
  const dir = workspace();
  const exportPath = writeExport(dir, [dlqRecord()]);
  const rowsPath = writeRows(dir, [FAILED_ROW]);
  const outPath = path.join(dir, "replay.jsonl");
  writeFileSync(`${outPath}.ledger.jsonl`, "{not-json\n");

  const summary = replay({
    exportPath,
    deliveryRowsPath: rowsPath,
    outPath,
    ledgerPath: `${outPath}.ledger.jsonl`,
    quarantinePath: `${outPath}.quarantine.jsonl`,
    dryRun: true,
  });

  assert.equal(summary.replayed, 1);
});

test("malformed records are written to the quarantine file", () => {
  const dir = workspace();
  const exportPath = writeExport(dir, [dlqRecord({ value: "{broken" })]);
  const rowsPath = writeRows(dir, [FAILED_ROW]);
  const outPath = path.join(dir, "replay.jsonl");

  const summary = replay({
    exportPath,
    deliveryRowsPath: rowsPath,
    outPath,
    ledgerPath: `${outPath}.ledger.jsonl`,
    quarantinePath: `${outPath}.quarantine.jsonl`,
    dryRun: false,
  });

  assert.equal(summary.quarantined.length, 1);
  assert.equal(summary.replayed, 0);
  const lines = readFileSync(`${outPath}.quarantine.jsonl`, "utf8").trim().split("\n");
  assert.equal(JSON.parse(lines[0]).reason, "invalid-json");
});

test("formatSummary renders every counter", () => {
  const summary = { scanned: 2, replayed: 1, skipped: [{ reason: "already-sent" }], quarantined: [], dryRun: false };
  assert.equal(
    formatSummary(summary),
    "scanned=2 replayed=1 skipped=1 quarantined=0 dryRun=false",
  );
});

test("main exits with the usage code on a bad invocation", () => {
  const errors = [];
  const originalWrite = process.stderr.write.bind(process.stderr);
  process.stderr.write = (chunk) => {
    errors.push(String(chunk));
    return true;
  };
  try {
    assert.equal(main(["--export", "only.jsonl"]), 2);
  } finally {
    process.stderr.write = originalWrite;
  }
  assert.match(errors.join(""), /usage: \S+ --export <dlq\.jsonl>/);
});

test("main replays a file end to end", () => {
  const dir = workspace();
  const exportPath = writeExport(dir, [dlqRecord()]);
  const rowsPath = writeRows(dir, [FAILED_ROW]);
  const outPath = path.join(dir, "replay.jsonl");
  const writes = [];
  const originalWrite = process.stdout.write.bind(process.stdout);
  process.stdout.write = (chunk) => {
    writes.push(String(chunk));
    return true;
  };
  try {
    assert.equal(
      main([
        "--export",
        exportPath,
        "--delivery-rows",
        rowsPath,
        "--out",
        outPath,
        "--dry-run",
      ]),
      0,
    );
  } finally {
    process.stdout.write = originalWrite;
  }
  assert.match(writes.join(""), /"replayed":1/);
});
