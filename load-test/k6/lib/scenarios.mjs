/**
 * Scenario and threshold definitions shared by the k6 script and its unit tests.
 * Threshold budgets (issue #129 §3.4):
 *  - analytics p95 < 2s
 *  - document presign p95 < 500ms
 *  - notification dispatch/fan-out p95 < 2s
 *  - webhook retry lag p95 < 10s
 */
export const THRESHOLDS = {
  "http_req_duration{scenario:analytics_ingestion}": ["p(95)<2000"],
  "http_req_duration{scenario:document_upload_download}": ["p(95)<500"],
  "document_presign_duration": ["p(95)<500"],
  "notification_fanout_latency": ["p(95)<2000"],
  "webhook_retry_lag": ["p(95)<10000"],
  "document_scan_duration": ["p(95)<5000"],
  "http_req_failed{scenario:multi_tenant_ramp}": ["rate<0.05"],
};

/** Full nightly profile: every scenario, the platform's production-shaped mix. */
export function fullOptions() {
  return {
    scenarios: {
      multi_tenant_ramp: {
        executor: "ramping-vus",
        startVUs: 1,
        stages: [
          { duration: "30s", target: 50 },
          { duration: "1m", target: 200 },
          { duration: "30s", target: 0 },
        ],
        exec: "tenantTraffic",
      },
      outbox_relay_burst: {
        executor: "constant-vus",
        vus: 20,
        duration: "1m",
        exec: "workflowBurst",
        startTime: "10s",
      },
      analytics_ingestion: {
        executor: "constant-arrival-rate",
        rate: 50,
        timeUnit: "1s",
        duration: "2m",
        preAllocatedVUs: 10,
        maxVUs: 50,
        exec: "analyticsIngestion",
        startTime: "20s",
      },
      document_upload_download: {
        executor: "constant-vus",
        vus: 10,
        duration: "2m",
        exec: "documentUploadDownload",
        startTime: "40s",
      },
      notification_fanout: {
        executor: "constant-arrival-rate",
        rate: 25,
        timeUnit: "1s",
        duration: "2m",
        preAllocatedVUs: 5,
        maxVUs: 30,
        exec: "notificationFanout",
        startTime: "60s",
      },
      webhook_retry_pressure: {
        executor: "constant-vus",
        vus: 5,
        duration: "2m",
        exec: "webhookRetryPressure",
        startTime: "30s",
      },
    },
    thresholds: THRESHOLDS,
  };
}

/** PR smoke profile: two scenarios, a handful of VUs, under five minutes. */
export function smokeOptions() {
  return {
    scenarios: {
      multi_tenant_ramp: {
        executor: "constant-vus",
        vus: 2,
        duration: "20s",
        exec: "tenantTraffic",
      },
      analytics_ingestion: {
        executor: "constant-arrival-rate",
        rate: 5,
        timeUnit: "1s",
        duration: "20s",
        preAllocatedVUs: 2,
        maxVUs: 5,
        exec: "analyticsIngestion",
        startTime: "5s",
      },
      webhook_retry_pressure: {
        executor: "per-vu-iterations",
        vus: 2,
        iterations: 3,
        exec: "webhookRetryPressure",
        startTime: "5s",
      },
    },
    thresholds: {
      ...THRESHOLDS,
      "http_req_failed{scenario:multi_tenant_ramp}": ["rate<0.10"],
    },
  };
}

/** Chooses the profile from K6_SMOKE so CI can reuse one script. */
export function optionsFor(env) {
  return env === "1" || env === "true" ? smokeOptions() : fullOptions();
}
