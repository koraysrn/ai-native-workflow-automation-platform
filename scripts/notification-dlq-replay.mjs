#!/usr/bin/env node
// Issue #205: safe, idempotent replay of notification dead letters.
//
// The notification DLQ is `notification.send.dlq`. An operator exports the records (for example
// with `kafka-console-consumer --topic notification.send.dlq --from-beginning` converted to JSON
// lines of `{topic,partition,offset,key,value}`) and exports the delivery rows that the payloads
// reference. This tool then decides, per record, whether a resend is safe:
//
//   * malformed / non-notification / id-less records are quarantined, never replayed;
//   * a delivery row that is already SENT or already has a provider message id is suppressed;
//   * a missing delivery row is suppressed (the dead letter cannot be trusted);
//   * re-running the tool suppresses a record it already replayed (ledger check).
//
// Nothing is published here: a replayable record appends one `notification.send` command line to
// `--out`, which the operator feeds back into the outbox/producer path. `--dry-run` prints the plan
// and writes nothing, so a rehearsal can never send a duplicate.

import { appendFileSync, existsSync, readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import { pathToFileURL } from "node:url";

export const NOTIFICATION_DLQ_TOPIC = "notification.send.dlq";
export const NOTIFICATION_TOPIC = "notification.send";
export const USAGE_EXIT_CODE = 2;

const REQUIRED = [
  ["exportPath", "--export"],
  ["deliveryRowsPath", "--delivery-rows"],
  ["outPath", "--out"],
];

/** Parses CLI arguments; throws on an unknown flag or a missing required one. */
export function parseArgs(argv) {
  const options = {
    exportPath: null,
    deliveryRowsPath: null,
    outPath: null,
    ledgerPath: null,
    quarantinePath: null,
    dryRun: false,
  };
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index];
    if (arg === "--export") {
      options.exportPath = argv[++index];
    } else if (arg === "--delivery-rows") {
      options.deliveryRowsPath = argv[++index];
    } else if (arg === "--out") {
      options.outPath = argv[++index];
    } else if (arg === "--ledger") {
      options.ledgerPath = argv[++index];
    } else if (arg === "--quarantine") {
      options.quarantinePath = argv[++index];
    } else if (arg === "--dry-run") {
      options.dryRun = true;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  const missing = REQUIRED.filter(([key]) => !options[key]).map(([, flag]) => flag);
  if (missing.length > 0) {
    throw new Error(`Missing required argument(s): ${missing.join(", ")}`);
  }
  options.ledgerPath = options.ledgerPath ?? `${options.outPath}.ledger.jsonl`;
  options.quarantinePath = options.quarantinePath ?? `${options.outPath}.quarantine.jsonl`;
  return options;
}

/** Stable idempotency key for one dead letter; the same record always maps to the same key. */
export function idempotencyKey(deliveryId, dedupeKey) {
  return createHash("sha256")
    .update(`${deliveryId}:${dedupeKey ?? "-"}`)
    .digest("hex")
    .slice(0, 32);
}

/**
 * Classifies one exported DLQ record.
 *
 * @returns {{status: "quarantined"|"skipped"|"replayable", reason?: string, record?: object}}
 */
export function evaluateRecord(entry, deliveryRows) {
  const record = entry.record;
  if (!record || typeof record !== "object") {
    return { status: "quarantined", reason: "invalid-export-line" };
  }
  if (record.topic !== NOTIFICATION_DLQ_TOPIC) {
    return { status: "skipped", reason: "not-a-notification-dlq" };
  }
  const payload = parsePayload(record.value);
  if (!payload) {
    return { status: "quarantined", reason: "invalid-json" };
  }
  const deliveryId = payload.deliveryId ?? record.key;
  if (!deliveryId) {
    return { status: "quarantined", reason: "missing-delivery-id" };
  }
  const row = deliveryRows.find((candidate) => candidate.id === deliveryId);
  if (!row) {
    return { status: "skipped", reason: "delivery-row-missing", deliveryId };
  }
  if (row.status === "SENT" || row.providerMessageId) {
    return { status: "skipped", reason: "already-sent", deliveryId };
  }
  if (!["FAILED", "RETRYING", "PENDING"].includes(row.status)) {
    return { status: "skipped", reason: "unexpected-status", deliveryId };
  }
  return {
    status: "replayable",
    deliveryId,
    dedupeKey: payload.dedupeKey,
    channel: payload.channel,
    reason: payload.reason,
  };
}

function parsePayload(value) {
  const raw = typeof value === "string" ? value : JSON.stringify(value ?? null);
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

/** Parses the JSONL export; blank lines are ignored and an unparsable line is kept for quarantine. */
export function parseExport(text) {
  const entries = [];
  for (const rawLine of text.split("\n")) {
    const line = rawLine.trim();
    if (line.length === 0) {
      continue;
    }
    try {
      entries.push({ record: JSON.parse(line) });
    } catch {
      entries.push({ record: null });
    }
  }
  return entries;
}

function loadLedger(ledgerPath) {
  if (!existsSync(ledgerPath)) {
    return new Set();
  }
  const keys = new Set();
  for (const line of readFileSync(ledgerPath, "utf8").split("\n")) {
    if (line.trim().length === 0) {
      continue;
    }
    try {
      const entry = JSON.parse(line);
      if (entry.idempotencyKey) {
        keys.add(entry.idempotencyKey);
      }
    } catch {
      // A corrupt ledger line must not stop a replay; the delivery-row check still guards it.
    }
  }
  return keys;
}

/**
 * Runs the replay. Returns the summary; only writes when `dryRun` is false.
 */
export function replay(options, now = new Date().toISOString()) {
  const entries = parseExport(readFileSync(options.exportPath, "utf8"));
  const deliveryRows = JSON.parse(readFileSync(options.deliveryRowsPath, "utf8"));
  const ledger = loadLedger(options.ledgerPath);
  const summary = { scanned: 0, replayed: 0, skipped: [], quarantined: [], dryRun: options.dryRun };

  for (const entry of entries) {
    summary.scanned++;
    const outcome = evaluateRecord(entry, deliveryRows);
    if (outcome.status === "quarantined") {
      summary.quarantined.push({ reason: outcome.reason });
      if (!options.dryRun) {
        appendFileSync(
          options.quarantinePath,
          `${JSON.stringify({ reason: outcome.reason, record: entry.record })}\n`,
        );
      }
      continue;
    }
    if (outcome.status === "skipped") {
      summary.skipped.push({ deliveryId: outcome.deliveryId ?? null, reason: outcome.reason });
      continue;
    }
    const key = idempotencyKey(outcome.deliveryId, outcome.dedupeKey);
    if (ledger.has(key)) {
      summary.skipped.push({ deliveryId: outcome.deliveryId, reason: "duplicate-replay" });
      continue;
    }
    ledger.add(key);
    summary.replayed++;
    if (options.dryRun) {
      continue;
    }
    const command = {
      topic: NOTIFICATION_TOPIC,
      key: outcome.deliveryId,
      payload: {
        deliveryId: outcome.deliveryId,
        dedupeKey: outcome.dedupeKey ?? null,
        channel: outcome.channel ?? null,
        replay: true,
        originalReason: outcome.reason ?? null,
      },
    };
    appendFileSync(options.outPath, `${JSON.stringify(command)}\n`);
    appendFileSync(
      options.ledgerPath,
      `${JSON.stringify({ idempotencyKey: key, deliveryId: outcome.deliveryId, replayedAt: now })}\n`,
    );
  }
  return summary;
}

export function formatSummary(summary) {
  return [
    `scanned=${summary.scanned}`,
    `replayed=${summary.replayed}`,
    `skipped=${summary.skipped.length}`,
    `quarantined=${summary.quarantined.length}`,
    `dryRun=${summary.dryRun}`,
  ].join(" ");
}

export function main(argv = process.argv.slice(2)) {
  let options;
  try {
    options = parseArgs(argv);
  } catch (error) {
    process.stderr.write(
      `${error.message}\nusage: ${path.basename(process.argv[1] ?? "notification-dlq-replay.mjs")}` +
        " --export <dlq.jsonl> --delivery-rows <rows.json> --out <replay.jsonl> [--dry-run]" +
        " [--ledger <file>] [--quarantine <file>]\n",
    );
    return USAGE_EXIT_CODE;
  }
  const summary = replay(options);
  process.stdout.write(`${JSON.stringify(summary)}\n${formatSummary(summary)}\n`);
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = main();
}
