import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";
import { decideAction, retryDelaySeconds } from "../lib/webhook-model.mjs";
import { optionsFor, THRESHOLDS } from "./lib/scenarios.mjs";

// Full nightly profile by default; PR smoke runs set K6_SMOKE=1 (low VUs, < 5 min).
export const options = optionsFor(__ENV.K6_SMOKE);

const analyticsLatency = new Trend("analytics_query_latency", true);
const documentPresignLatency = new Trend("document_presign_duration", true);
const documentScanDuration = new Trend("document_scan_duration", true);
const notificationLatency = new Trend("notification_fanout_latency", true);
const webhookRetryLag = new Trend("webhook_retry_lag", true);
const webhookRetries = new Counter("webhook_retries");

const BASE = () => __ENV.API_BASE || "http://localhost:8080";
const WEBHOOK_TARGET = () => __ENV.WEBHOOK_TARGET || "http://localhost:9099/webhook";

function headers(extra = {}) {
  const result = { "Content-Type": "application/json", ...extra };
  if (__ENV.TENANT_ID) {
    result["X-Tenant-Id"] = __ENV.TENANT_ID;
  }
  if (__ENV.ACCESS_TOKEN) {
    result.Authorization = `Bearer ${__ENV.ACCESS_TOKEN}`;
  }
  return result;
}

/** Accepts success or an auth rejection so an unauthenticated stack still measures latency. */
function reachable(status) {
  return status === 200 || status === 201 || status === 401 || status === 403;
}

export function setup() {
  // CI seeds a tenant and passes TENANT_ID/ACCESS_TOKEN through the environment.
  return { tenantId: __ENV.TENANT_ID || "", tokenPresent: Boolean(__ENV.ACCESS_TOKEN) };
}

export function tenantTraffic() {
  const base = BASE();

  const health = http.get(`${base}/actuator/health`, { tags: { scenario: "multi_tenant_ramp" } });
  check(health, { "health is 200": (r) => r.status === 200 });

  const workflows = http.get(`${base}/api/v1/workflow-instances`, {
    headers: headers(),
    tags: { scenario: "multi_tenant_ramp" },
  });
  check(workflows, { "workflows respond": (r) => reachable(r.status) });

  sleep(1);
}

export function workflowBurst() {
  const base = BASE();

  // Burst of workflow-start requests exercises the durable outbox relay path:
  // request -> service -> outbox write -> Kafka producer -> consumer lag.
  const payload = JSON.stringify({
    workflowDefinitionId: __ENV.WORKFLOW_DEFINITION_ID || "00000000-0000-0000-0000-000000000000",
    contextJson: "{}",
  });
  const started = http.post(`${base}/api/v1/workflow-instances`, payload, {
    headers: headers(),
    tags: { scenario: "outbox_relay_burst" },
  });
  check(started, {
    "start accepted or auth-required": (r) =>
      r.status === 201 || r.status === 202 || r.status === 400 || reachable(r.status),
  });

  sleep(0.1);
}

export function analyticsIngestion() {
  const started = Date.now();
  const response = http.get(`${BASE()}/api/v1/analytics/summary`, {
    headers: headers(),
    tags: { scenario: "analytics_ingestion" },
  });
  analyticsLatency.add(Date.now() - started, { scenario: "analytics_ingestion" });

  check(response, { "analytics summary reachable": (r) => reachable(r.status) });
  sleep(0.1);
}

export function documentUploadDownload() {
  const base = BASE();
  const payload = JSON.stringify({
    name: `loadtest-${__VU}-${__ITER}.txt`,
    contentType: "text/plain",
    size: 1024,
  });

  const presignStarted = Date.now();
  const presign = http.post(`${base}/api/v1/documents`, payload, {
    headers: headers(),
    tags: { scenario: "document_upload_download" },
  });
  documentPresignLatency.add(Date.now() - presignStarted, {
    scenario: "document_upload_download",
  });
  check(presign, { "document presign reachable": (r) => reachable(r.status) });

  const listStarted = Date.now();
  const listing = http.get(`${base}/api/v1/documents`, {
    headers: headers(),
    tags: { scenario: "document_upload_download" },
  });
  if (listing.status === 200) {
    documentScanDuration.add(Date.now() - listStarted, {
      scenario: "document_upload_download",
    });
  }
  check(listing, { "document list reachable": (r) => reachable(r.status) });

  sleep(0.2);
}

export function notificationFanout() {
  const base = BASE();
  const started = Date.now();
  const inbox = http.get(`${base}/api/v1/notifications?unreadOnly=true`, {
    headers: headers(),
    tags: { scenario: "notification_fanout" },
  });
  notificationLatency.add(Date.now() - started, { scenario: "notification_fanout" });

  const unread = http.get(`${base}/api/v1/notifications/unread-count`, {
    headers: headers(),
    tags: { scenario: "notification_fanout" },
  });
  check(inbox, { "notification inbox reachable": (r) => reachable(r.status) });
  check(unread, { "unread count reachable": (r) => reachable(r.status) });

  sleep(0.2);
}

export function webhookRetryPressure() {
  const target = WEBHOOK_TARGET();
  const started = Date.now();
  let attempt = 0;
  let response;
  let retries = 0;

  while (attempt < 5) {
    attempt += 1;
    response = http.post(target, JSON.stringify({ tenantId: __ENV.TENANT_ID || "", seq: __ITER }), {
      headers: { "Content-Type": "application/json" },
      tags: { scenario: "webhook_retry_pressure" },
    });
    const action = decideAction(response.status, response.headers["Retry-After"]);
    if (action !== "retry") {
      break;
    }
    retries += 1;
    webhookRetries.add(1, { scenario: "webhook_retry_pressure" });
    sleep(retryDelaySeconds(response.status, response.headers["Retry-After"], attempt, 100));
  }

  webhookRetryLag.add(Date.now() - started, { scenario: "webhook_retry_pressure" });
  check(response, {
    "webhook target answered": (r) => r !== undefined && r.status > 0,
    "webhook retries stayed bounded": () => retries < 5,
  });

  sleep(0.2);
}

// Keep the documented thresholds close to the script for reviewers.
export const thresholds = THRESHOLDS;
