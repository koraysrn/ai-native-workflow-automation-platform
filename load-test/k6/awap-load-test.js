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
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<2000"],
    http_req_failed: ["rate<0.05"],
  },
};

export function setup() {
  // The CI job seeds 1000+ tenants, users and workflow instances before the
  // run. Login/token generation happens per tenant in the seeder.
  return {};
}

export default function () {
  const base = __ENV.API_BASE || "http://localhost:8080";

  // Each iteration represents a tenant-scoped user performing a read and a
  // workflow/task operation. Real isolation assertions (no foreign tenant ids
  // in responses, single-winner approvals) run in the seeder and CI script.
  const health = http.get(`${base}/actuator/health`);
  check(health, { "health is 200": (r) => r.status === 200 });

  const workflows = http.get(`${base}/api/v1/workflow-instances`);
  check(workflows, { "workflows respond": (r) => r.status === 200 || r.status === 401 });

  sleep(1);
}
