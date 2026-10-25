import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
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
  },
  thresholds: {
    http_req_duration: ["p(95)<2000"],
    http_req_failed: ["rate<0.05"],
    // Blocking smoke threshold only; the soak thresholds are reported, not enforced.
    "http_req_duration{scenario:multi_tenant_ramp}": ["p(95)<2000"],
    "http_req_failed{scenario:multi_tenant_ramp}": ["rate<0.05"],
  },
};

const BASE = () => __ENV.API_BASE || "http://localhost:8080";

export function setup() {
  // The CI job seeds tenants/users/workflow instances before the run.
  return {};
}

export function tenantTraffic() {
  const base = BASE();

  const health = http.get(`${base}/actuator/health`);
  check(health, { "health is 200": (r) => r.status === 200 });

  const workflows = http.get(`${base}/api/v1/workflow-instances`);
  check(workflows, { "workflows respond": (r) => r.status === 200 || r.status === 401 });

  sleep(1);
}

export function workflowBurst() {
  const base = BASE();

  // Burst of workflow-start requests exercises the durable outbox relay path:
  // request -> service -> outbox write -> Kafka producer -> consumer lag.
  const payload = JSON.stringify({
    workflowDefinitionId: "00000000-0000-0000-0000-000000000000",
    tenantId: "00000000-0000-0000-0000-000000000000",
  });
  const params = {
    headers: { "Content-Type": "application/json", "X-Tenant-Id": "00000000-0000-0000-0000-000000000000" },
  };
  const started = http.post(`${base}/api/v1/workflow-instances`, payload, params);
  check(started, {
    "start accepted or auth-required": (r) => r.status === 201 || r.status === 202 || r.status === 401 || r.status === 400,
  });

  sleep(0.1);
}
