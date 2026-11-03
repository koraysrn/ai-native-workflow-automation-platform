// Live end-to-end verification of the Debezium outbox connector (issue #161).
//
// The harness runs against the real Compose stack:
//   * Kafka Connect is registered with `infra/debezium/register-connector.sh`
//   * a new outbox row is written directly to Postgres and must appear on
//     `outbox.event.<aggregate_type>` (CDC) and on the polling topic
//   * the exact CDC payload is replayed twice on `notification.send`; the existing
//     notification de-duplication key must absorb the duplicates
//   * the connector is stopped, the replication slot becomes inactive, `AwapCdcSlotInactive`
//     fires, and the rows created during the gap are streamed after resume without loss
//   * the 512 MiB slot-lag rule is loaded and healthy (its threshold is covered by promtool)
//
// Usage:
//   node load-test/cdc-e2e.mjs --tenant-id <uuid> --output load-test/results/cdc-e2e.json
//   node load-test/cdc-e2e.mjs --tenant-id <uuid> --skip-alerts   # quick local smoke
//
// The stack must already be running (see load-test/README.md). The command exits non-zero when any
// acceptance assertion fails and always writes the JSON artifact for review.
import { execFile, spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import {
  buildE2eReport,
  buildNotificationRequestEvent,
  buildOutboxEvent,
  decodeKafkaValue,
  findStreamedRecord,
  parseConnectorStatus,
  parseCount,
  parsePrometheusAlertStates,
  parseReplicationSlot,
  parseTopicRecords,
  requireUuid,
  sqlLiteral,
  sumPrometheusMetric,
} from "./lib/cdc-e2e.mjs";

const execFileAsync = promisify(execFile);
const ROOT = fileURLToPath(new URL("../", import.meta.url));
const CONNECTOR_NAME = "awap-outbox-connector";
const SLOT_NAME = "awap_outbox";

/** Filled as soon as the CLI parses arguments so a failed run still writes its artifact. */
const currentRun = {
  options: null,
  checks: [],
  startedAt: null,
  runId: null,
};

function usage() {
  return `usage: node load-test/cdc-e2e.mjs [options]

options:
  --output <path>              JSON artifact (default load-test/results/cdc-e2e.json)
  --run-id <id>                marker used in payloads (default CDC_E2E_RUN_ID or timestamp)
  --tenant-id <uuid>           tenant to use (default TENANT_ID env, else load-test/seed.json)
  --connect-url <url>          Kafka Connect REST endpoint (default http://localhost:8083)
  --prometheus-url <url>       Prometheus endpoint used for alert state (default http://localhost:9090)
  --backend-url <url>          Backend actuator endpoint (default http://localhost:8080)
  --alert-wait-ms <n>          wait for AwapCdcSlotInactive to fire (default 420000)
  --wait-timeout-ms <n>        connector/slot/topic wait budget (default 120000)
  --kafka-timeout-ms <n>       console-consumer idle timeout (default 8000)
  --compose-file <path>        main Compose file (default infra/docker-compose.yml)
  --compose-project <name>     Compose project name (default COMPOSE_PROJECT_NAME)
  --postgres-service <name>    Compose service name (default postgres)
  --kafka-service <name>       Compose service name (default kafka)
  --skip-alerts                do not wait for Prometheus alerts (local smoke only)
  --help                       show this help`;
}

function parseArgs(argv) {
  const options = {
    output: "load-test/results/cdc-e2e.json",
    runId: process.env.CDC_E2E_RUN_ID ?? `cdc-e2e-${Date.now()}-${process.pid}`,
    tenantId: process.env.TENANT_ID ?? "",
    connectUrl: process.env.CONNECT_URL ?? "http://localhost:8083",
    prometheusUrl: process.env.PROMETHEUS_URL ?? "http://localhost:9090",
    backendUrl: process.env.API_BASE ?? "http://localhost:8080",
    alertWaitMs: 420_000,
    waitTimeoutMs: 120_000,
    kafkaTimeoutMs: 8_000,
    composeFile: "infra/docker-compose.yml",
    composeProject: process.env.COMPOSE_PROJECT_NAME ?? null,
    postgresService: process.env.POSTGRES_SERVICE ?? "postgres",
    kafkaService: process.env.KAFKA_SERVICE ?? "kafka",
    postgresUser: process.env.POSTGRES_USER ?? "awap",
    postgresDb: process.env.POSTGRES_DB ?? "awap",
    skipAlerts: false,
    help: false,
  };

  const valueFlags = {
    "--output": "output",
    "--run-id": "runId",
    "--tenant-id": "tenantId",
    "--connect-url": "connectUrl",
    "--prometheus-url": "prometheusUrl",
    "--backend-url": "backendUrl",
    "--alert-wait-ms": "alertWaitMs",
    "--wait-timeout-ms": "waitTimeoutMs",
    "--kafka-timeout-ms": "kafkaTimeoutMs",
    "--compose-file": "composeFile",
    "--compose-project": "composeProject",
    "--postgres-service": "postgresService",
    "--kafka-service": "kafkaService",
  };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--help" || argument === "-h") {
      options.help = true;
    } else if (argument === "--skip-alerts") {
      options.skipAlerts = true;
    } else if (valueFlags[argument]) {
      const value = argv[index + 1];
      if (value === undefined) {
        throw new Error(`${argument} requires a value`);
      }
      options[valueFlags[argument]] = value;
      index += 1;
    } else {
      throw new Error(`unknown argument: ${argument}`);
    }
  }

  for (const key of ["alertWaitMs", "waitTimeoutMs", "kafkaTimeoutMs"]) {
    options[key] = Number.parseInt(options[key], 10);
    if (!Number.isFinite(options[key]) || options[key] < 0) {
      throw new Error(`${key} must be a non-negative integer`);
    }
  }
  if (options.tenantId) {
    options.tenantId = requireUuid(options.tenantId, "--tenant-id");
  }
  return options;
}

function sleep(ms) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

function composeArgs(options, args) {
  const result = ["compose"];
  if (options.composeProject) {
    result.push("-p", options.composeProject);
  }
  result.push("-f", options.composeFile, ...args);
  return result;
}

async function runCompose(options, args, { allowFailure = false, env = process.env } = {}) {
  try {
    const { stdout, stderr } = await execFileAsync("docker", composeArgs(options, args), {
      cwd: ROOT,
      env,
      maxBuffer: 64 * 1024 * 1024,
      timeout: 120_000,
    });
    return { stdout, stderr, code: 0 };
  } catch (error) {
    if (allowFailure) {
      return {
        stdout: error.stdout ?? "",
        stderr: error.stderr ?? error.message,
        code: typeof error.code === "number" ? error.code : 1,
      };
    }
    throw new Error(
      `docker ${composeArgs(options, args).join(" ")} failed: ${error.stderr ?? error.message}`,
    );
  }
}

/** Compose exec with stdin, used by kafka-console-producer. */
function runComposeWithInput(options, args, input) {
  return new Promise((resolveRun, rejectRun) => {
    const child = spawn("docker", composeArgs(options, args), { cwd: ROOT });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("error", rejectRun);
    child.on("close", (code) => resolveRun({ stdout, stderr, code: code ?? 1 }));
    child.stdin.end(input);
  });
}

async function psql(options, sql) {
  const result = await runCompose(options, [
    "exec",
    "-T",
    options.postgresService,
    "psql",
    "-U",
    options.postgresUser,
    "-d",
    options.postgresDb,
    "-t",
    "-A",
    "-F",
    "\t",
    "-v",
    "ON_ERROR_STOP=1",
    "-c",
    sql,
  ]);
  return result.stdout;
}

async function runRegisterScript(options) {
  const { stdout, stderr } = await execFileAsync("bash", [
    resolve(ROOT, "infra/debezium/register-connector.sh"),
  ], {
    cwd: ROOT,
    maxBuffer: 16 * 1024 * 1024,
    env: {
      ...process.env,
      CONNECT_URL: options.connectUrl,
      DB_HOST: options.postgresService,
      DB_PORT: "5432",
      DB_USER: options.postgresUser,
      DB_PASSWORD: process.env.POSTGRES_PASSWORD ?? "awap",
      DB_NAME: options.postgresDb,
    },
  });
  return `${stdout}${stderr}`.trim();
}

async function connectRequest(options, path, { method = "GET", body } = {}) {
  const base = options.connectUrl.replace(/\/+$/, "");
  const response = await fetch(`${base}${path}`, {
    method,
    headers: body ? { "Content-Type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15_000),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Connect ${method} ${path} returned ${response.status}: ${text.slice(0, 400)}`);
  }
  return text.trim() ? JSON.parse(text) : null;
}

async function readConnectorStatus(options) {
  return parseConnectorStatus(
    await connectRequest(options, `/connectors/${CONNECTOR_NAME}/status`),
  );
}

async function waitForCondition(description, predicate, { timeoutMs, pollMs = 1_000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;
  while (Date.now() <= deadline) {
    try {
      const result = await predicate();
      if (result) {
        return result;
      }
    } catch (error) {
      lastError = error;
    }
    await sleep(pollMs);
  }
  throw new Error(
    `${description} did not become true within ${timeoutMs}ms` +
      (lastError ? `: ${lastError.message}` : ""),
  );
}

async function waitForConnectorRunning(options) {
  return waitForCondition(
    `connector ${CONNECTOR_NAME} RUNNING`,
    async () => {
      const status = await readConnectorStatus(options);
      return status.running ? status : false;
    },
    { timeoutMs: options.waitTimeoutMs, pollMs: 3_000 },
  );
}

async function waitForConnectorState(options, expected) {
  return waitForCondition(
    `connector ${CONNECTOR_NAME} ${expected}`,
    async () => {
      const status = await readConnectorStatus(options);
      return status.connectorState === expected ? status : false;
    },
    { timeoutMs: options.waitTimeoutMs, pollMs: 2_000 },
  );
}

async function waitForRowPublished(options, eventId) {
  return waitForCondition(
    `outbox row ${eventId} published by the polling relay`,
    async () => {
      const output = await psql(
        options,
        `SELECT published FROM outbox_events WHERE id = ${sqlLiteral(eventId)};`,
      );
      return output.trim() === "t" || output.trim() === "true";
    },
    { timeoutMs: options.waitTimeoutMs, pollMs: 1_000 },
  );
}

async function insertOutboxEvent(options, event) {
  await psql(options, event.sql);
  const count = Number.parseInt(
    (
      await psql(
        options,
        `SELECT count(*) FROM outbox_events WHERE id = ${sqlLiteral(event.id)};`,
      )
    ).trim(),
    10,
  );
  if (count !== 1) {
    throw new Error(`expected one outbox row for ${event.id}, found ${count}`);
  }
}

async function consumeTopic(options, topic, { maxMessages = 5_000, timeoutMs } = {}) {
  const result = await runCompose(
    options,
    [
      "exec",
      "-T",
      options.kafkaService,
      "kafka-console-consumer",
      "--bootstrap-server",
      "localhost:9092",
      "--topic",
      topic,
      "--from-beginning",
      "--timeout-ms",
      String(timeoutMs ?? options.kafkaTimeoutMs),
      "--max-messages",
      String(maxMessages),
      "--property",
      "print.timestamp=true",
      "--property",
      "print.headers=true",
      "--property",
      "print.key=true",
    ],
    { allowFailure: true },
  );
  return { records: parseTopicRecords(result.stdout), code: result.code, stderr: result.stderr };
}

async function waitForRecord(options, topic, match, { timeoutMs } = {}) {
  const deadline = Date.now() + (timeoutMs ?? options.waitTimeoutMs);
  let last = { records: [] };
  while (Date.now() <= deadline) {
    last = await consumeTopic(options, topic);
    const record = last.records.find(match);
    if (record) {
      return { record, records: last.records };
    }
    await sleep(2_000);
  }
  throw new Error(
    `no matching record on ${topic} within ${timeoutMs ?? options.waitTimeoutMs}ms ` +
      `(last read ${last.records.length} record(s))`,
  );
}

async function produceTopic(options, topic, values) {
  const result = await runComposeWithInput(
    options,
    [
      "exec",
      "-T",
      options.kafkaService,
      "kafka-console-producer",
      "--bootstrap-server",
      "localhost:9092",
      "--topic",
      topic,
    ],
    `${values.join("\n")}\n`,
  );
  if (result.code !== 0) {
    throw new Error(`kafka-console-producer ${topic} failed: ${result.stderr.slice(0, 400)}`);
  }
}

async function readSlot(options) {
  const output = await psql(
    options,
    "SELECT active, pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)::bigint " +
      `FROM pg_replication_slots WHERE slot_name = ${sqlLiteral(SLOT_NAME)};`,
  );
  return parseReplicationSlot(output);
}

async function waitForSlotInactive(options) {
  return waitForCondition(
    `replication slot ${SLOT_NAME} inactive`,
    async () => {
      const slot = await readSlot(options);
      return slot.present && !slot.active ? slot : false;
    },
    { timeoutMs: options.waitTimeoutMs, pollMs: 2_000 },
  );
}

async function waitForSlotActive(options) {
  return waitForCondition(
    `replication slot ${SLOT_NAME} active`,
    async () => {
      const slot = await readSlot(options);
      return slot.present && slot.active ? slot : false;
    },
    { timeoutMs: options.waitTimeoutMs, pollMs: 2_000 },
  );
}

async function readAlertStates(options) {
  const base = options.prometheusUrl.replace(/\/+$/, "");
  const response = await fetch(`${base}/api/v1/rules?type=alert`, {
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) {
    throw new Error(`Prometheus rules API returned ${response.status}`);
  }
  return parsePrometheusAlertStates(await response.json());
}

async function waitForAlertState(options, alertName, expectedState, timeoutMs) {
  return waitForCondition(
    `alert ${alertName} ${expectedState}`,
    async () => {
      const states = await readAlertStates(options);
      const rule = states[alertName];
      return rule?.state === expectedState ? rule : false;
    },
    { timeoutMs, pollMs: 5_000 },
  );
}

async function readSuppressedCounter(options) {
  try {
    const response = await fetch(`${options.backendUrl.replace(/\/+$/, "")}/actuator/prometheus`, {
      signal: AbortSignal.timeout(5_000),
    });
    if (!response.ok) {
      return null;
    }
    return sumPrometheusMetric(
      await response.text(),
      "awap_notification_dedupe_suppressed_total",
    );
  } catch {
    return null;
  }
}

async function resolveTenantId(options) {
  if (options.tenantId) {
    return options.tenantId;
  }
  try {
    const seeded = JSON.parse(
      await readFile(resolve(ROOT, "load-test/seed.json"), "utf8"),
    );
    const tenantId = seeded.tenants?.[0]?.tenantId;
    if (tenantId) {
      return requireUuid(tenantId, "seed tenantId");
    }
  } catch {
    // Fall through to the actionable error below.
  }
  throw new Error(
    "no tenant available: pass --tenant-id or run `node load-test/seed.mjs` first",
  );
}

async function writeReport(options, report) {
  const path = resolve(ROOT, options.output);
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  return path;
}

function reportEnvironment(options) {
  return {
    composeProject: options.composeProject,
    connectorName: CONNECTOR_NAME,
    slotName: SLOT_NAME,
    connectUrl: options.connectUrl,
    prometheusUrl: options.skipAlerts ? null : options.prometheusUrl,
    tenantId: options.tenantId,
    relayIntervalMs:
      Number.parseInt(process.env.OUTBOX_RELAY_INTERVAL_MS ?? "0", 10) || null,
    kafkaEnabled: process.env.KAFKA_ENABLED ?? null,
  };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    console.log(usage());
    return;
  }

  const startedAt = new Date().toISOString();
  const checks = [];
  const pass = (id, title, detail) =>
    checks.push({ id, title, status: "passed", detail });
  const skip = (id, title, detail) =>
    checks.push({ id, title, status: "skipped", detail });
  currentRun.options = options;
  currentRun.checks = checks;
  currentRun.startedAt = startedAt;

  options.tenantId = await resolveTenantId(options);
  const runId = options.runId;
  currentRun.runId = runId;

  // 1. Kafka Connect starts and the connector is registered idempotently.
  const registration = await runRegisterScript(options);
  const running = await waitForConnectorRunning(options);
  pass(
    "compose-connector-registered",
    "Compose stack starts Debezium Connect and registers awap-outbox-connector",
    {
      connectorState: running.connectorState,
      taskStates: running.taskStates,
      registration: registration.split(/\r?\n/).slice(-1)[0] ?? null,
    },
  );

  // 2. A new outbox row is streamed to outbox.event.<aggregate_type>.
  const taskEventId = randomUUID();
  const taskAggregateId = randomUUID();
  const taskEvent = buildOutboxEvent({
    id: taskEventId,
    tenantId: options.tenantId,
    aggregateType: "TASK",
    aggregateId: taskAggregateId,
    eventType: "TASK_COMPLETED",
    payload: { eventId: taskEventId, runId, marker: "cdc-e2e-task" },
  });
  await insertOutboxEvent(options, taskEvent);
  await waitForRowPublished(options, taskEvent.id);

  const cdcRecord = await waitForRecord(
    options,
    "outbox.event.TASK",
    (record) => String(record.headers?.id ?? "").toLowerCase() === taskEvent.id.toLowerCase(),
  );
  if (cdcRecord.record.key !== taskAggregateId) {
    throw new Error(
      `CDC record key ${cdcRecord.record.key} does not match aggregate ${taskAggregateId}`,
    );
  }
  if (cdcRecord.record.headers?.tenantId !== options.tenantId) {
    throw new Error(
      `CDC record tenant header ${cdcRecord.record.headers?.tenantId} does not match ${options.tenantId}`,
    );
  }
  if (!decodeKafkaValue(cdcRecord.record.value).includes("cdc-e2e-task")) {
    throw new Error("CDC record value is missing the harness marker");
  }
  pass(
    "outbox-row-streamed-to-aggregate-topic",
    "A new outbox row is streamed to outbox.event.TASK",
    {
      eventId: taskEvent.id,
      topic: "outbox.event.TASK",
      key: cdcRecord.record.key,
      tenantHeader: cdcRecord.record.headers?.tenantId ?? null,
    },
  );

  // 3. The same event is consumed through the polling path during the dual run.
  const pollingRecord = await waitForRecord(
    options,
    "workflow.task.events",
    (record) => record.value.includes(taskEvent.id),
  );
  if (pollingRecord.record.key !== taskAggregateId) {
    throw new Error(
      `polling record key ${pollingRecord.record.key} does not match aggregate ${taskAggregateId}`,
    );
  }
  pass(
    "dual-run-polling-consumed",
    "The same event is consumed from the polling topic during the dual run",
    {
      eventId: taskEvent.id,
      topic: "workflow.task.events",
      key: pollingRecord.record.key,
    },
  );

  // 4. The exact CDC payload replayed twice is absorbed by the notification dedupe key.
  const notificationEvent = buildNotificationRequestEvent({
    tenantId: options.tenantId,
    runId,
  });
  const notificationOutbox = buildOutboxEvent({
    tenantId: options.tenantId,
    aggregateType: "NOTIFICATION",
    eventType: "NOTIFICATION_REQUESTED",
    payload: notificationEvent,
  });
  await insertOutboxEvent(options, notificationOutbox);
  const deliveryCountSql =
    "SELECT count(*) FROM notification_deliveries" +
    ` WHERE tenant_id = ${sqlLiteral(options.tenantId)}` +
    ` AND business_key = ${sqlLiteral(notificationEvent.businessKey)};`;
  await waitForCondition(
    "the first notification delivery row",
    async () => Number.parseInt((await psql(options, deliveryCountSql)).trim(), 10) === 1,
    { timeoutMs: options.waitTimeoutMs, pollMs: 1_000 },
  );

  const notificationCdc = await waitForRecord(
    options,
    "outbox.event.NOTIFICATION",
    (record) =>
      String(record.headers?.id ?? "").toLowerCase() ===
      notificationOutbox.id.toLowerCase(),
  );
  const notificationPayload = decodeKafkaValue(notificationCdc.record.value);
  if (!notificationPayload.includes(notificationEvent.dedupeKey)) {
    throw new Error("CDC notification payload does not contain the explicit dedupeKey");
  }

  const suppressedBefore = await readSuppressedCounter(options);
  await produceTopic(options, "notification.send", [
    notificationPayload,
    notificationPayload,
  ]);
  await sleep(5_000);

  const duplicateDeliveryCount = Number.parseInt(
    (await psql(options, deliveryCountSql)).trim(),
    10,
  );
  const suppressedAfter = await readSuppressedCounter(options);
  if (duplicateDeliveryCount !== 1) {
    throw new Error(
      `duplicate CDC payloads created ${duplicateDeliveryCount} notification deliveries, expected 1`,
    );
  }
  if (
    suppressedBefore !== null &&
    suppressedAfter !== null &&
    suppressedAfter <= suppressedBefore
  ) {
    throw new Error(
      `dedupe suppression counter did not advance (${suppressedBefore} -> ${suppressedAfter})`,
    );
  }
  pass(
    "duplicate-events-absorbed",
    "Duplicate events are absorbed by the existing notification dedupe key",
    {
      cdcEventId: notificationOutbox.id,
      replayCount: 2,
      deliveryRows: duplicateDeliveryCount,
      suppressedBefore,
      suppressedAfter,
      dedupeKey: notificationEvent.dedupeKey,
      businessKey: notificationEvent.businessKey,
    },
  );

  // 5. Stop the connector: the slot goes inactive and the inactive-slot alert fires.
  await connectRequest(options, `/connectors/${CONNECTOR_NAME}/stop`, { method: "PUT" });
  const stopped = await waitForConnectorState(options, "STOPPED");
  const inactiveSlot = await waitForSlotInactive(options);
  // The connector checkpoints while stopping, so the lag baseline must be taken after the slot is
  // inactive; only WAL written from this point on is guaranteed to be retained by the stopped slot.
  const slotBeforeGap = await readSlot(options);

  const gapEvents = Array.from({ length: 3 }, (_, index) => {
    const id = randomUUID();
    return buildOutboxEvent({
      id,
      tenantId: options.tenantId,
      aggregateType: "TASK",
      aggregateId: randomUUID(),
      eventType: "TASK_COMPLETED",
      payload: { eventId: id, runId, marker: `cdc-e2e-gap-${index}` },
    });
  });
  for (const event of gapEvents) {
    await insertOutboxEvent(options, event);
    await waitForRowPublished(options, event.id);
  }
  const slotAfterGap = await readSlot(options);
  if (slotAfterGap.lagBytes < slotBeforeGap.lagBytes) {
    throw new Error(
      `replication slot lag decreased while the connector was stopped ` +
        `(${slotBeforeGap.lagBytes} -> ${slotAfterGap.lagBytes})`,
    );
  }
  if (slotAfterGap.lagBytes <= 0) {
    throw new Error("the stopped connector retained no WAL for the gap rows");
  }
  const gapCdc = await consumeTopic(options, "outbox.event.TASK", {
    timeoutMs: options.kafkaTimeoutMs,
  });
  const streamedWhileStopped = gapEvents.filter((event) =>
    findStreamedRecord(gapCdc.records, { eventId: event.id }),
  );
  if (streamedWhileStopped.length > 0) {
    throw new Error("the connector streamed new rows while it was stopped");
  }

  pass(
    "connector-stop-makes-slot-inactive",
    "Stopping the connector leaves the awap_outbox slot inactive while the polling relay keeps draining",
    {
      connectorState: stopped.connectorState,
      slotActive: inactiveSlot.active,
      slotLagBytes: slotAfterGap.lagBytes,
      gapEventsPublishedByPolling: gapEvents.length,
      gapEventsStreamedByCdc: streamedWhileStopped.length,
    },
  );

  if (options.skipAlerts) {
    skip(
      "inactive-slot-alert-fires",
      "AwapCdcSlotInactive fires while the connector is stopped",
      "skipped with --skip-alerts",
    );
    skip(
      "slot-lag-rule-loaded",
      "The 512 MiB slot-lag rule is loaded and healthy",
      "skipped with --skip-alerts",
    );
  } else {
    const firing = await waitForAlertState(
      options,
      "AwapCdcSlotInactive",
      "firing",
      options.alertWaitMs,
    );
    pass(
      "inactive-slot-alert-fires",
      "AwapCdcSlotInactive fires while the connector is stopped",
      {
        state: firing.state,
        health: firing.health,
        alertCount: firing.alerts.length,
        slotLagBytes: slotAfterGap.lagBytes,
      },
    );

    const states = await readAlertStates(options);
    const lagRule = states.AwapCdcSlotLagHigh;
    if (!lagRule || lagRule.health !== "ok") {
      throw new Error(
        `AwapCdcSlotLagHigh is not loaded and healthy: ${JSON.stringify(lagRule ?? null)}`,
      );
    }
    pass(
      "slot-lag-rule-loaded",
      "The 512 MiB slot-lag rule is loaded and healthy in Prometheus",
      {
        state: lagRule.state,
        health: lagRule.health,
        slotLagBytes: slotAfterGap.lagBytes,
        thresholdBytes: 512 * 1024 * 1024,
      },
    );
  }

  // 6. Resume: the rows created while stopped are streamed without loss and the alert resolves.
  await connectRequest(options, `/connectors/${CONNECTOR_NAME}/resume`, { method: "PUT" });
  const resumed = await waitForConnectorRunning(options);
  const activeSlot = await waitForSlotActive(options);
  pass(
    "connector-resumed",
    "The connector resumes and the replication slot becomes active again",
    {
      connectorState: resumed.connectorState,
      taskStates: resumed.taskStates,
      slotActive: activeSlot.active,
    },
  );

  const resumedEvents = {};
  for (const event of gapEvents) {
    const record = await waitForRecord(
      options,
      "outbox.event.TASK",
      (candidate) =>
        String(candidate.headers?.id ?? "").toLowerCase() === event.id.toLowerCase(),
    );
    resumedEvents[event.id] = record.record.timestampMs;
  }
  pass(
    "resume-without-loss",
    "Every row created while the connector was stopped is streamed after resume",
    {
      eventIds: Object.keys(resumedEvents),
      streamedRecords: resumedEvents,
    },
  );

  if (options.skipAlerts) {
    skip(
      "inactive-slot-alert-resolves",
      "AwapCdcSlotInactive resolves after resume",
      "skipped with --skip-alerts",
    );
  } else {
    const resolved = await waitForAlertState(
      options,
      "AwapCdcSlotInactive",
      "inactive",
      120_000,
    );
    pass(
      "inactive-slot-alert-resolves",
      "AwapCdcSlotInactive resolves after resume",
      { state: resolved.state, health: resolved.health },
    );
  }

  const report = buildE2eReport({
    runId,
    environment: reportEnvironment(options),
    checks,
    startedAt,
    finishedAt: new Date().toISOString(),
  });
  const path = await writeReport(options, report);
  console.log(
    JSON.stringify(
      {
        runId,
        passed: report.passed,
        summary: report.summary,
        output: path,
      },
      null,
      2,
    ),
  );
  if (!report.passed) {
    process.exit(1);
  }
}

main().catch(async (error) => {
  if (currentRun.options) {
    currentRun.checks.push({
      id: "e2e-harness",
      title: "End-to-end harness completed",
      status: "failed",
      detail: error.message,
    });
    const report = buildE2eReport({
      runId: currentRun.runId ?? currentRun.options.runId,
      environment: reportEnvironment(currentRun.options),
      checks: currentRun.checks,
      startedAt: currentRun.startedAt ?? new Date().toISOString(),
      finishedAt: new Date().toISOString(),
    });
    try {
      const path = await writeReport(currentRun.options, report);
      console.error(`CDC e2e report written to ${path}`);
    } catch (writeError) {
      console.error(`Could not write the CDC e2e report: ${writeError.message}`);
    }
  }
  console.error(error.stack ?? error.message);
  process.exit(1);
});
