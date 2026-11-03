import assert from "node:assert/strict";
import { test } from "node:test";
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
} from "../lib/cdc-e2e.mjs";

const TENANT = "11111111-1111-1111-1111-111111111111";
const EVENT = "22222222-2222-2222-2222-222222222222";
const AGGREGATE = "33333333-3333-3333-3333-333333333333";

test("sqlLiteral quotes values and doubles embedded single quotes", () => {
  assert.equal(sqlLiteral("plain"), "'plain'");
  assert.equal(sqlLiteral("it's"), "'it''s'");
  assert.equal(sqlLiteral(null), "NULL");
});

test("requireUuid rejects malformed identifiers", () => {
  assert.equal(requireUuid(TENANT, "tenant"), TENANT);
  assert.throws(() => requireUuid("not-a-uuid", "tenant"), /must be a UUID/);
});

test("buildOutboxEvent creates a deterministic outbox INSERT with RETURNING id", () => {
  const event = buildOutboxEvent({
    id: EVENT,
    tenantId: TENANT,
    aggregateType: "TASK",
    aggregateId: AGGREGATE,
    eventType: "TASK_COMPLETED",
    payload: { marker: "o'clock" },
  });

  assert.equal(event.id, EVENT);
  assert.equal(event.payloadJson, '{"marker":"o\'clock"}');
  assert.match(event.sql, /INSERT INTO outbox_events/);
  assert.match(event.sql, /'TASK_COMPLETED'/);
  assert.match(event.sql, /o''clock/);
  assert.match(event.sql, /now\(\), false\) RETURNING id;/);
});

test("buildOutboxEvent rejects empty payloads and required fields", () => {
  assert.throws(
    () =>
      buildOutboxEvent({
        id: EVENT,
        tenantId: TENANT,
        aggregateType: "TASK",
        aggregateId: AGGREGATE,
        eventType: "TASK_COMPLETED",
        payload: "",
      }),
    /payload/,
  );
  assert.throws(
    () =>
      buildOutboxEvent({
        id: EVENT,
        tenantId: TENANT,
        aggregateType: "",
        aggregateId: AGGREGATE,
        eventType: "TASK_COMPLETED",
        payload: {},
      }),
    /aggregateType/,
  );
});

test("buildNotificationRequestEvent carries the explicit dedupe and business keys", () => {
  const event = buildNotificationRequestEvent({ tenantId: TENANT, runId: "run-1" });
  assert.equal(event.tenantId, TENANT);
  assert.equal(event.dedupeKey, "cdc-e2e-run-1");
  assert.equal(event.businessKey, "cdc-e2e-run-1");
  assert.equal(event.channel, "WEB");
  assert.equal(event.templateKey, "notification");
  assert.deepEqual(JSON.parse(event.variablesJson), { message: "CDC e2e run-1" });

  assert.throws(
    () => buildNotificationRequestEvent({ tenantId: TENANT, runId: "" }),
    /runId/,
  );
});

test("parseConnectorStatus requires a RUNNING connector and every task RUNNING", () => {
  assert.deepEqual(parseConnectorStatus({ connector: { state: "RUNNING" }, tasks: [{ state: "RUNNING" }] }), {
    connectorState: "RUNNING",
    taskStates: ["RUNNING"],
    running: true,
  });
  assert.equal(
    parseConnectorStatus({ connector: { state: "RUNNING" }, tasks: [{ state: "FAILED" }] }).running,
    false,
  );
  assert.equal(
    parseConnectorStatus({ connector: { state: "RUNNING" }, tasks: [] }).running,
    false,
  );
  assert.equal(parseConnectorStatus("{}").connectorState, "UNKNOWN");
});

test("parseReplicationSlot handles absent, inactive and active slots", () => {
  assert.deepEqual(parseReplicationSlot(""), { present: false, active: false, lagBytes: 0 });
  assert.deepEqual(parseReplicationSlot("f\t1048576\n"), {
    present: true,
    active: false,
    lagBytes: 1_048_576,
  });
  assert.deepEqual(parseReplicationSlot("t\t0\n"), {
    present: true,
    active: true,
    lagBytes: 0,
  });
});

test("parseTopicRecords reads EventRouter headers, keys and values", () => {
  const stdout = [
    `CreateTime:1762100000123\tid:${EVENT},tenantId:${TENANT}\t${AGGREGATE}\t{"state":"STARTED"}`,
    "CreateTime:1762100000456\t\taggregate-key\t{\"eventId\":\"no-header\"}",
    "Processed a total of 2 messages",
  ].join("\n");

  assert.deepEqual(parseTopicRecords(stdout), [
    {
      timestampMs: 1762100000123,
      headers: { id: EVENT, tenantId: TENANT },
      key: AGGREGATE,
      value: '{"state":"STARTED"}',
    },
    {
      timestampMs: 1762100000456,
      headers: {},
      key: "aggregate-key",
      value: '{"eventId":"no-header"}',
    },
  ]);
});

test("parseTopicRecords supports the header-before-timestamp formatter", () => {
  const stdout = `id:${EVENT},tenantId:${TENANT}\tCreateTime:1762100000999\t${AGGREGATE}\t{}`;
  assert.deepEqual(parseTopicRecords(stdout), [
    {
      timestampMs: 1762100000999,
      headers: { id: EVENT, tenantId: TENANT },
      key: AGGREGATE,
      value: "{}",
    },
  ]);
});

test("decodeKafkaValue unwraps the JSON string literal emitted by EventRouter", () => {
  const inner = '{"eventId":"22222222-2222-2222-2222-222222222222"}';
  assert.equal(decodeKafkaValue(JSON.stringify(inner)), inner);
  assert.equal(decodeKafkaValue(inner), inner);
  assert.equal(decodeKafkaValue("plain"), "plain");
  assert.equal(decodeKafkaValue(null), "");
});

test("findStreamedRecord matches the id header and payload marker", () => {
  const records = parseTopicRecords(
    `CreateTime:1762100000123\tid:${EVENT},tenantId:${TENANT}\t${AGGREGATE}\t{"marker":"cdc-e2e-task"}`,
  );

  assert.equal(findStreamedRecord(records, { eventId: EVENT })?.key, AGGREGATE);
  assert.equal(findStreamedRecord(records, { payloadMarker: "cdc-e2e-task" })?.key, AGGREGATE);
  assert.equal(findStreamedRecord(records, { eventId: EVENT, payloadMarker: "other" }), null);
  assert.equal(findStreamedRecord(records, { eventId: AGGREGATE }), null);
});

test("findStreamedRecord decodes a JSON-string CDC payload before matching", () => {
  const encoded = JSON.stringify('{"marker":"cdc-e2e-task"}');
  const records = parseTopicRecords(
    `CreateTime:1762100000123\tid:${EVENT},tenantId:${TENANT}\t${AGGREGATE}\t${encoded}`,
  );

  assert.equal(findStreamedRecord(records, { payloadMarker: "cdc-e2e-task" })?.key, AGGREGATE);
});

test("parsePrometheusAlertStates flattens alerting rules by name", () => {
  const states = parsePrometheusAlertStates({
    data: {
      groups: [
        {
          rules: [
            {
              type: "alerting",
              name: "AwapCdcSlotInactive",
              state: "firing",
              health: "ok",
              alerts: [{ labels: { severity: "critical" } }],
            },
            { type: "recording", name: "ignored", state: "ok" },
          ],
        },
      ],
    },
  });

  assert.deepEqual(states, {
    AwapCdcSlotInactive: {
      state: "firing",
      health: "ok",
      alerts: [{ labels: { severity: "critical" } }],
    },
  });
});

test("sumPrometheusMetric sums only exact metric name matches", () => {
  const text = [
    "# HELP awap_notification_dedupe_suppressed_total suppressed",
    'awap_notification_dedupe_suppressed_total{reason="existing"} 2',
    'awap_notification_dedupe_suppressed_total{reason="constraint"} 1.5',
    "awap_notification_dedupe_suppressed_extra 100",
  ].join("\n");

  assert.equal(sumPrometheusMetric(text, "awap_notification_dedupe_suppressed_total"), 3.5);
  assert.equal(sumPrometheusMetric(text, "awap_notification_dedupe_suppressed"), 0);
});

test("parseCount tolerates psql whitespace and rejects non-numeric output", () => {
  assert.equal(parseCount(" 3\n"), 3);
  assert.equal(parseCount("not-a-number"), 0);
});

test("buildE2eReport summarizes passed, failed and skipped assertions", () => {
  const report = buildE2eReport({
    runId: "run-1",
    environment: { composeProject: "awap-cdc-e2e" },
    startedAt: "2026-11-03T00:00:00.000Z",
    finishedAt: "2026-11-03T00:01:00.000Z",
    checks: [
      { id: "a", title: "passes", status: "passed", detail: { value: 1 } },
      { id: "b", title: "skips", status: "skipped" },
    ],
  });

  assert.equal(report.issue, 161);
  assert.equal(report.passed, true);
  assert.deepEqual(report.summary, { total: 2, passed: 1, failed: 0, skipped: 1 });
  assert.deepEqual(report.assertions[0], {
    id: "a",
    title: "passes",
    status: "passed",
    detail: { value: 1 },
  });
  assert.equal(report.assertions[1].detail, null);
});

test("buildE2eReport is failed when any assertion failed", () => {
  const report = buildE2eReport({
    runId: "run-1",
    checks: [
      { id: "a", title: "passes", status: "passed" },
      { id: "b", title: "fails", status: "failed", detail: "boom" },
    ],
  });

  assert.equal(report.passed, false);
  assert.deepEqual(report.summary, { total: 2, passed: 1, failed: 1, skipped: 0 });
});
