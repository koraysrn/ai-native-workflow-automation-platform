/**
 * Pure helpers for the polling-vs-CDC outbox benchmark (issue #159).
 *
 * The collector produces raw measurements; this module turns them into the same p50/p95/max
 * summary plus the `evaluateCutover()` verdict that the runbook and ADR-0004 refer to.
 */
import { CDC_TRIGGERS, evaluateCutover, percentile } from "./cdc-triggers.mjs";

export const BENCHMARK_SCHEMA_VERSION = 1;

const UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

function finiteNumber(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

/** Accepts a raw array or a `{ samples: [...] }` document and keeps finite numbers only. */
export function normalizeSamples(payload, label = "samples", { allowEmpty = false } = {}) {
  const raw = Array.isArray(payload) ? payload : payload?.samples;
  if (!Array.isArray(raw) || raw.length === 0) {
    if (allowEmpty) {
      return [];
    }
    throw new Error(`${label} must contain a non-empty "samples" array`);
  }
  const samples = raw.map(Number).filter((value) => Number.isFinite(value));
  if (samples.length === 0 && !allowEmpty) {
    throw new Error(`${label} contained no finite numeric samples`);
  }
  return samples;
}

/** Nearest-rank p50/p95 plus max for one mode. */
export function summarize(label, samples) {
  const values = samples.map(Number).filter((value) => Number.isFinite(value));
  return {
    label,
    count: values.length,
    p50: percentile(values, 50),
    p95: percentile(values, 95),
    max: values.length === 0 ? null : Math.max(...values),
    samples: values,
  };
}

/** CDC is present when the connector was registered, even if it emitted no records yet. */
export function isCdcRegistered(payload) {
  if (!payload) {
    return false;
  }
  if (Array.isArray(payload)) {
    return payload.length > 0;
  }
  return payload.registered === true || (Array.isArray(payload.samples) && payload.samples.length > 0);
}

/**
 * Builds the JSON document printed by `cdc-benchmark.mjs`.
 *
 * Missing CDC data is not an error here: polling remains the default and a benchmark run without
 * the connector must still produce a useful polling-only report. `--require-cdc` turns the absence
 * into a failure in the CLI.
 */
export function buildBenchmarkReport({
  polling,
  cdc = { registered: false, samples: [] },
  cutoverInputs = {},
  thresholds = CDC_TRIGGERS,
  generatedAt = new Date().toISOString(),
  sources = {},
  environment = {},
} = {}) {
  const pollingSummary = summarize("polling", normalizeSamples(polling, "polling"));
  const cdcRegistered = isCdcRegistered(cdc);
  const cdcSummary = cdcRegistered
    ? summarize("cdc", normalizeSamples(cdc, "cdc", { allowEmpty: true }))
    : { label: "cdc", count: 0, p50: null, p95: null, max: null, samples: [] };

  const measured = {
    publishLagP95Ms: pollingSummary.p95,
    consecutiveBacklogSweeps: finiteNumber(cutoverInputs.consecutiveBacklogSweeps),
    slotLagBytes: finiteNumber(cutoverInputs.slotLagBytes),
    walGrowthBytesPerMin: finiteNumber(cutoverInputs.walGrowthBytesPerMin),
  };
  const verdict = evaluateCutover(measured);

  return {
    schemaVersion: BENCHMARK_SCHEMA_VERSION,
    generatedAt,
    environment,
    sources,
    thresholds,
    cutoverInputs: measured,
    collection: {
      polling: {
        windowSeconds: polling?.windowSeconds ?? null,
        tenantId: polling?.tenantId ?? null,
        pendingAfterWait: polling?.pendingAfterWait ?? null,
      },
      cdc: {
        windowSeconds: cdc?.windowSeconds ?? null,
        tenantId: cdc?.tenantId ?? null,
        outboxRows: cdc?.outboxRows ?? null,
        kafkaRecords: cdc?.kafkaRecords ?? null,
        matched: cdc?.matched ?? null,
        duplicateRecords: cdc?.duplicateRecords ?? null,
        unmatchedRecords: cdc?.unmatchedRecords ?? null,
      },
    },
    polling: pollingSummary,
    cdc: {
      ...cdcSummary,
      registered: cdcRegistered,
      connectorState: cdc?.connectorState ?? null,
      reason: cdc?.reason ?? null,
    },
    improvementP95Ms:
      cdcSummary.count > 0 ? pollingSummary.p95 - cdcSummary.p95 : null,
    verdict,
  };
}

/** Parses `psql -t -A` output where every line is one lag sample in milliseconds. */
export function parsePsqlSamples(stdout) {
  return String(stdout)
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((line) => Number(line))
    .filter((value) => Number.isFinite(value))
    .map((value) => Math.max(0, Math.round(value)));
}

/**
 * Parses `psql -t -A -F '\t'` output of `SELECT id, created_at_ms FROM outbox_events`.
 * `id` is the outbox event id, which Debezium's EventRouter copies into the `id` header.
 */
export function parseOutboxRows(stdout) {
  const rows = [];
  for (const rawLine of String(stdout).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) {
      continue;
    }
    const [id, createdAtMs] = line.split("\t");
    if (!new RegExp(`^${UUID}$`, "i").test(id ?? "")) {
      continue;
    }
    const createdAt = Number(createdAtMs);
    if (Number.isFinite(createdAt)) {
      rows.push({ id: id.toLowerCase(), createdAtMs: createdAt });
    }
  }
  return rows;
}

/**
 * Parses `kafka-console-consumer` output produced with
 * `--property print.timestamp=true --property print.headers=true`.
 *
 * A line looks like `CreateTime:1762100000000\tid:<uuid>,tenantId:<uuid>\t<key>\t<value>`.
 * Timestamp and header order are not relied on, and the JSON value cannot be mistaken for the
 * header because a JSON field is written as `"id":` (with the quote) rather than `id:`.
 */
export function parseConsoleConsumerRecords(stdout) {
  const records = [];
  for (const rawLine of String(stdout).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) {
      continue;
    }
    const timestamp = line.match(/CreateTime:(\d+)/i);
    const eventId = line.match(new RegExp(`\\bid:(${UUID})`, "i"));
    if (timestamp && eventId) {
      records.push({
        eventId: eventId[1].toLowerCase(),
        publishedAtMs: Number(timestamp[1]),
      });
    }
  }
  return records;
}

/** Joins Kafka records to outbox rows by event id and computes `record timestamp - created_at`. */
export function computeCdcLags(outboxRows, records) {
  const createdAtById = new Map(outboxRows.map((row) => [row.id, row.createdAtMs]));
  const seen = new Set();
  const samples = [];
  let matched = 0;
  let duplicateRecords = 0;
  let unmatchedRecords = 0;

  for (const record of records) {
    if (!createdAtById.has(record.eventId)) {
      unmatchedRecords += 1;
      continue;
    }
    if (seen.has(record.eventId)) {
      duplicateRecords += 1;
      continue;
    }
    seen.add(record.eventId);
    matched += 1;
    const lag = record.publishedAtMs - createdAtById.get(record.eventId);
    if (Number.isFinite(lag)) {
      // A few milliseconds of app/broker clock skew must not turn into a negative lag sample.
      samples.push(Math.max(0, Math.round(lag)));
    }
  }

  return {
    samples,
    matched,
    unmatched: outboxRows.length - matched,
    duplicateRecords,
    unmatchedRecords,
  };
}
