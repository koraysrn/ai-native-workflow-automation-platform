import assert from "node:assert/strict";
import { test } from "node:test";
import { fullOptions, optionsFor, smokeOptions, THRESHOLDS } from "../k6/lib/scenarios.mjs";

function totalDurationSeconds(options) {
  return Math.max(
    ...Object.values(options.scenarios).map((scenario) => {
      const start = Number.parseInt(String(scenario.startTime ?? "0s"), 10);
      const duration = Number.parseInt(String(scenario.duration ?? "0s"), 10);
      return start + duration;
    }),
  );
}

test("full profile covers every scenario from the issue", () => {
  const scenarios = fullOptions().scenarios;
  for (const name of [
    "analytics_ingestion",
    "document_upload_download",
    "notification_fanout",
    "webhook_retry_pressure",
    "multi_tenant_ramp",
    "outbox_relay_burst",
  ]) {
    assert.ok(scenarios[name], `missing scenario ${name}`);
    assert.ok(typeof scenarios[name].exec === "string");
  }
});

test("thresholds enforce the documented budgets", () => {
  assert.deepEqual(THRESHOLDS["http_req_duration{scenario:analytics_ingestion}"], ["p(95)<2000"]);
  assert.deepEqual(THRESHOLDS.document_presign_duration, ["p(95)<500"]);
  assert.deepEqual(THRESHOLDS.notification_fanout_latency, ["p(95)<2000"]);
  assert.deepEqual(THRESHOLDS.webhook_retry_lag, ["p(95)<10000"]);
  assert.deepEqual(
    fullOptions().thresholds["http_req_duration{scenario:document_upload_download}"],
    ["p(95)<500"],
  );
});

test("smoke profile runs in under five minutes with a handful of VUs", () => {
  const smoke = smokeOptions();
  assert.ok(totalDurationSeconds(smoke) < 300, "smoke run must stay under 5 minutes");
  const vus = Object.values(smoke.scenarios).map((scenario) =>
    Number.parseInt(String(scenario.vus ?? scenario.preAllocatedVUs ?? "0"), 10),
  );
  assert.ok(Math.max(...vus) <= 5, "smoke run must stay at low VUs");
  assert.equal(smoke.thresholds["http_req_failed{scenario:multi_tenant_ramp}"][0], "rate<0.10");
});

test("optionsFor selects the smoke profile only when requested", () => {
  assert.deepEqual(optionsFor("1"), smokeOptions());
  assert.deepEqual(optionsFor("true"), smokeOptions());
  assert.deepEqual(optionsFor(undefined), fullOptions());
});
