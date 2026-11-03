/**
 * Pure helpers for the live Debezium outbox end-to-end check (issue #161).
 *
 * The helpers stay free of Docker/network access so the parsing, SQL construction and report
 * summary can be covered by `node --test` on every pull request. The CLI in
 * `load-test/cdc-e2e.mjs` wires them to the Compose stack.
 */
import { randomUUID } from "node:crypto";

export const CDC_E2E_SCHEMA_VERSION = 1;
export const CDC_E2E_ISSUE = 161;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function requireUuid(value, label) {
  if (!UUID.test(String(value ?? ""))) {
    throw new Error(`${label} must be a UUID`);
  }
  return String(value);
}

/** Quotes one PostgreSQL string literal, including embedded single quotes. */
export function sqlLiteral(value) {
  if (value === null || value === undefined) {
    return "NULL";
  }
  return `'${String(value).replace(/'/g, "''")}'`;
}

/**
 * Builds the deterministic INSERT the harness uses to create a new outbox row without going through
 * the API. `RETURNING id` lets the caller assert that exactly one row was written.
 */
export function buildOutboxEvent({
  id = randomUUID(),
  tenantId,
  aggregateType,
  aggregateId = randomUUID(),
  eventType,
  payload,
  createdAt = "now()",
  published = false,
} = {}) {
  requireUuid(id, "id");
  requireUuid(tenantId, "tenantId");
  requireUuid(aggregateId, "aggregateId");
  if (!aggregateType || !String(aggregateType).trim()) {
    throw new Error("aggregateType is required");
  }
  if (!eventType || !String(eventType).trim()) {
    throw new Error("eventType is required");
  }
  const payloadJson = typeof payload === "string" ? payload : JSON.stringify(payload);
  if (payloadJson === undefined || payloadJson === null || payloadJson.trim() === "") {
    throw new Error("payload must serialize to a non-empty JSON document");
  }

  const sql =
    "INSERT INTO outbox_events" +
    " (id, tenant_id, aggregate_type, aggregate_id, event_type, payload_json, created_at, published)" +
    ` VALUES (${sqlLiteral(id)}, ${sqlLiteral(tenantId)}, ${sqlLiteral(aggregateType)},` +
    ` ${sqlLiteral(aggregateId)}, ${sqlLiteral(eventType)}, ${sqlLiteral(payloadJson)},` +
    ` ${createdAt}, ${published ? "true" : "false"}) RETURNING id;`;

  return {
    id,
    tenantId,
    aggregateType,
    aggregateId,
    eventType,
    payloadJson,
    createdAt,
    published,
    sql,
  };
}

/**
 * Notification payload consumed from `notification.send`. The explicit `dedupeKey`/`businessKey`
 * make the duplicate-absorption assertion queryable on `notification_deliveries`.
 */
export function buildNotificationRequestEvent({
  tenantId,
  runId,
  dedupeKey = `cdc-e2e-${runId}`,
  businessKey = `cdc-e2e-${runId}`,
  recipient = "cdc-e2e@example.com",
  channel = "WEB",
  templateKey = "notification",
  locale = "en",
  message = `CDC e2e ${runId}`,
} = {}) {
  requireUuid(tenantId, "tenantId");
  if (!runId) {
    throw new Error("runId is required");
  }
  return {
    tenantId,
    recipient,
    channel,
    templateKey,
    locale,
    variablesJson: JSON.stringify({ message }),
    dedupeKey,
    businessKey,
    eventType: "TASK_COMPLETED",
  };
}

/** Parses the Connector REST `/status` document used by the readiness assertions. */
export function parseConnectorStatus(payload) {
  const body = typeof payload === "string" ? JSON.parse(payload) : payload;
  const tasks = Array.isArray(body?.tasks) ? body.tasks : [];
  const taskStates = tasks.map((task) => task?.state ?? "UNKNOWN");
  const connectorState = body?.connector?.state ?? "UNKNOWN";
  return {
    connectorState,
    taskStates,
    running:
      connectorState === "RUNNING" &&
      taskStates.length > 0 &&
      taskStates.every((state) => state === "RUNNING"),
  };
}

/**
 * Parses `psql -t -A -F '\t'` output for
 * `SELECT active, pg_wal_lsn_diff(...)::bigint FROM pg_replication_slots`.
 * An absent slot is represented instead of throwing so the harness can report it.
 */
export function parseReplicationSlot(stdout) {
  const line = String(stdout)
    .split(/\r?\n/)
    .map((entry) => entry.trim())
    .find((entry) => entry.length > 0);
  if (!line) {
    return { present: false, active: false, lagBytes: 0 };
  }
  const [active, lag] = line.split("\t");
  const lagBytes = Number(lag);
  return {
    present: true,
    active: active === "t" || active === "true",
    lagBytes: Number.isFinite(lagBytes) ? Math.max(0, lagBytes) : 0,
  };
}

function parseHeaders(headerPart) {
  const headers = {};
  for (const entry of String(headerPart ?? "").split(",")) {
    const separator = entry.indexOf(":");
    if (separator > 0) {
      headers[entry.slice(0, separator).trim()] = entry.slice(separator + 1).trim();
    }
  }
  return headers;
}

/**
 * Parses `kafka-console-consumer` output produced with
 * `--property print.timestamp=true --property print.headers=true`.
 *
 * A record is `CreateTime:<ms>\t<headers>\t<key>\t<value>`. Polling topics have no headers, so the
 * header field is empty; JSON values may contain any character except the tab separator used by
 * the console formatter.
 */
export function parseTopicRecords(stdout) {
  const records = [];
  for (const rawLine of String(stdout).split(/\r?\n/)) {
    const line = rawLine.trim();
    const timestampIndex = line
      .split("\t")
      .findIndex((part) => /^CreateTime:\d+$/.test(part));
    if (timestampIndex < 0) {
      continue;
    }
    const parts = line.split("\t");
    const timestampMs = Number(parts[timestampIndex].split(":")[1]);
    let headerPart = "";
    let keyIndex = timestampIndex + 1;
    if (timestampIndex > 0) {
      // Older console formatters print headers before the timestamp.
      headerPart = parts[0];
      keyIndex = timestampIndex + 1;
    } else {
      headerPart = parts[1] ?? "";
      keyIndex = 2;
    }
    const key = parts[keyIndex] === undefined || parts[keyIndex] === "null" ? null : parts[keyIndex];
    const value = parts.slice(keyIndex + 1).join("\t");
    records.push({
      timestampMs,
      headers: parseHeaders(headerPart),
      key,
      value,
    });
  }
  return records;
}

/**
 * Debezium's `JsonConverter` serializes the EventRouter string payload as a JSON string literal, so
 * a CDC record value can be `"{\"id\":\"...\"}"` while a polling record value is the object itself.
 * Decoding one level gives the payload the existing consumers parse.
 */
export function decodeKafkaValue(value) {
  const text = String(value ?? "");
  try {
    const parsed = JSON.parse(text);
    return typeof parsed === "string" ? parsed : text;
  } catch {
    return text;
  }
}

/** Finds a record by the EventRouter `id` header and/or a payload marker. */
export function findStreamedRecord(records, { eventId, payloadMarker } = {}) {
  const normalizedId = eventId ? String(eventId).toLowerCase() : null;
  return (
    (Array.isArray(records) ? records : []).find(
      (record) =>
        (!normalizedId || String(record.headers?.id ?? "").toLowerCase() === normalizedId) &&
        (!payloadMarker || decodeKafkaValue(record.value).includes(payloadMarker)),
    ) ?? null
  );
}

/** Parses the Prometheus `/api/v1/rules?type=alert` response into a name -> state map. */
export function parsePrometheusAlertStates(payload) {
  const body = typeof payload === "string" ? JSON.parse(payload) : payload;
  const groups = Array.isArray(body?.data?.groups) ? body.data.groups : [];
  const states = {};
  for (const group of groups) {
    for (const rule of Array.isArray(group.rules) ? group.rules : []) {
      if (rule?.type !== "alerting" || !rule.name) {
        continue;
      }
      states[rule.name] = {
        state: rule.state ?? "inactive",
        health: rule.health ?? "unknown",
        alerts: Array.isArray(rule.alerts) ? rule.alerts : [],
      };
    }
  }
  return states;
}

/** Sums every sample of one counter/gauge in a Prometheus text exposition. */
export function sumPrometheusMetric(text, metric) {
  let total = 0;
  for (const rawLine of String(text).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) {
      continue;
    }
    if (!line.startsWith(`${metric}{`) && !line.startsWith(`${metric} `)) {
      continue;
    }
    const value = Number(line.split(/\s+/).pop());
    if (Number.isFinite(value)) {
      total += value;
    }
  }
  return total;
}

/** Converts `psql -t -A` count output into a finite integer. */
export function parseCount(stdout) {
  const value = Number.parseInt(String(stdout).trim(), 10);
  return Number.isFinite(value) ? value : 0;
}

/**
 * Builds the JSON artifact committed with the run. One entry per acceptance item makes the report
 * directly reviewable against issue #161 instead of a wall of console output.
 */
export function buildE2eReport({
  runId,
  issue = CDC_E2E_ISSUE,
  environment = {},
  checks = [],
  startedAt = new Date().toISOString(),
  finishedAt = new Date().toISOString(),
} = {}) {
  if (!runId) {
    throw new Error("runId is required");
  }
  const assertions = checks.map((check) => ({
    id: check.id,
    title: check.title,
    status: check.status,
    detail: check.detail ?? null,
  }));
  const summary = assertions.reduce(
    (totals, assertion) => {
      totals.total += 1;
      if (assertion.status === "passed") {
        totals.passed += 1;
      } else if (assertion.status === "failed") {
        totals.failed += 1;
      } else {
        totals.skipped += 1;
      }
      return totals;
    },
    { total: 0, passed: 0, failed: 0, skipped: 0 },
  );
  return {
    schemaVersion: CDC_E2E_SCHEMA_VERSION,
    issue,
    runId,
    startedAt,
    generatedAt: finishedAt,
    environment,
    assertions,
    summary,
    passed: summary.failed === 0,
  };
}
