/**
 * 1000+ tenant load test (issue #201): concurrent tenant traffic, RLS/ownership assertions,
 * double-approval races and Idempotency-Key replays.
 *
 * The tenant list is produced by `node load-test/seed-tenants.mjs` and read through a SharedArray so
 * 1000 VUs share one parsed copy. Each VU is pinned to one tenant and every response is checked for
 * cross-tenant leakage, so "tenants run in parallel" cannot hide an isolation breach.
 */
import http from "k6/http";
import { check, sleep } from "k6";
import { SharedArray } from "k6/data";
import { Counter, Trend } from "k6/metrics";
import {
  APPROVAL_RACE_ATTEMPTS,
  assertOwnRows,
  classifyApprovalRace,
  classifyCrossTenantProbe,
  evaluateIdempotentStart,
  foreignTenant,
  tenantForVu,
  tenantHeaders,
} from "../lib/tenancy.mjs";
import { tenancyOptionsFor } from "./lib/tenancy-scenarios.mjs";

// k6 resolves `open()` relative to this script, so the default points back at load-test/tenants.json.
// An override must be an absolute path (CI passes $GITHUB_WORKSPACE/load-test/tenants.json).
const tenants = new SharedArray("tenants", () => {
  const path = __ENV.TENANTS_FILE || "../tenants.json";
  return JSON.parse(open(path)).tenants;
});

export const options = tenancyOptionsFor(
  __ENV.K6_SMOKE,
  Number.parseInt(__ENV.TENANT_COUNT || String(tenants.length), 10),
);

const isolationViolations = new Counter("isolation_violations");
const crossTenantLeaks = new Counter("cross_tenant_leaks");
const approvalRaceViolations = new Counter("approval_race_violations");
const idempotencyViolations = new Counter("idempotency_violations");
const approvalDecisionLatency = new Trend("approval_decision_latency", true);

const BASE = () => __ENV.API_BASE || "http://localhost:8080";

// A race legitimately answers 409 for the losers; k6 would otherwise count those as failed requests
// and the error-rate SLO would punish the correct behaviour.
const RACE_STATUSES = http.expectedStatuses({ min: 200, max: 299 }, 409);
// A cross-tenant read must be denied: 403/404 are expected, a 2xx stays a failure and is counted.
const DENIED_STATUSES = http.expectedStatuses(403, 404);

export function setup() {
  if (!Array.isArray(tenants) || tenants.length < 2) {
    throw new Error(
      "The tenancy load test needs at least two seeded tenants; run node load-test/seed-tenants.mjs",
    );
  }
  return { tenants: tenants.length };
}

/**
 * Steady per-tenant traffic. The list call must return only the caller's rows and a neighbour's
 * instance must be invisible; both are checked on every iteration, not just once.
 */
export function tenantIsolation() {
  const tenant = tenantForVu(tenants, __VU);
  const own = http.get(`${BASE()}/api/v1/workflow-instances`, {
    headers: tenantHeaders(tenant),
    tags: { scenario: "tenant_isolation" },
  });
  const rows = own.status === 200 ? own.json() : undefined;
  const verdict = assertOwnRows(rows, tenant.tenantId);
  if (own.status !== 200 || !verdict.ok) {
    isolationViolations.add(1);
  }
  check(own, {
    "own list answered 200": (r) => r.status === 200,
    "own list contains only own rows": () => verdict.ok,
  });

  const foreign = foreignTenant(tenants, __VU);
  if (foreign?.workflowInstanceId) {
    const probe = http.get(`${BASE()}/api/v1/workflow-instances/${foreign.workflowInstanceId}`, {
      headers: tenantHeaders(tenant),
      tags: { scenario: "tenant_isolation" },
      responseCallback: DENIED_STATUSES,
    });
    const crossVerdict = classifyCrossTenantProbe(probe.status);
    if (crossVerdict.leak) {
      crossTenantLeaks.add(1);
    }
    check(probe, {
      "foreign instance is denied": () => crossVerdict.ok,
    });
  }

  sleep(0.5);
}

/**
 * One PENDING task, five concurrent approvals. Exactly one 200 and four 409s prove the task
 * lifecycle is serializable; any other split is a production defect and fails the run.
 */
export function approvalRace() {
  const tenant = tenantForVu(tenants, __VU);
  const created = http.post(
    `${BASE()}/api/v1/tasks`,
    JSON.stringify({
      title: `race-${tenant.index}-${__VU}-${__ITER}`,
      assigneeId: tenant.userId,
    }),
    { headers: tenantHeaders(tenant), tags: { scenario: "approval_race" } },
  );
  if (created.status !== 201) {
    approvalRaceViolations.add(1);
    return;
  }
  const taskId = created.json("id");
  const approveUrl = `${BASE()}/api/v1/tasks/${taskId}/approve`;
  const requests = Array.from({ length: APPROVAL_RACE_ATTEMPTS }, () => [
    "POST",
    approveUrl,
    "{}",
    {
      headers: tenantHeaders(tenant),
      tags: { scenario: "approval_race" },
      responseCallback: RACE_STATUSES,
    },
  ]);

  const started = Date.now();
  const responses = http.batch(requests);
  approvalDecisionLatency.add(Date.now() - started);
  const verdict = classifyApprovalRace(responses.map((response) => response.status));
  if (!verdict.ok) {
    approvalRaceViolations.add(1);
  }
  check(responses, {
    "exactly one approval wins": () => verdict.ok,
    "no approval returned a server error": (response) => response.status !== 500,
  });

  sleep(0.2);
}

/**
 * The same Idempotency-Key twice must return the first instance (or a 409 while it is still in
 * flight), never a second instance. This is the request-level guarantee the API filter provides.
 */
export function idempotentStart() {
  const tenant = tenantForVu(tenants, __VU);
  const key = `tenancy-load-${tenant.index}-${__VU}-${__ITER}`;
  const payload = JSON.stringify({
    workflowDefinitionId: tenant.workflowDefinitionId,
    contextJson: "{}",
  });
  const headers = tenantHeaders(tenant, { "Idempotency-Key": key });
  const first = http.post(`${BASE()}/api/v1/workflow-instances`, payload, {
    headers,
    tags: { scenario: "idempotent_start" },
    responseCallback: http.expectedStatuses({ min: 200, max: 299 }),
  });
  const second = http.post(`${BASE()}/api/v1/workflow-instances`, payload, {
    headers,
    tags: { scenario: "idempotent_start" },
    responseCallback: RACE_STATUSES,
  });
  const verdict = evaluateIdempotentStart(
    { status: first.status, id: first.status < 300 ? first.json("id") : undefined },
    { status: second.status, id: second.status < 300 ? second.json("id") : undefined },
  );
  if (!verdict.ok) {
    idempotencyViolations.add(1);
  }
  check(second, {
    "duplicate key does not create a second instance": () => verdict.ok,
  });

  sleep(0.2);
}
