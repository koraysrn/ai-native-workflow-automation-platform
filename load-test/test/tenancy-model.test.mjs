import assert from "node:assert/strict";
import { test } from "node:test";
import {
  APPROVAL_RACE_ATTEMPTS,
  assertOwnRows,
  buildTenantFixture,
  chunk,
  classifyApprovalRace,
  classifyCrossTenantProbe,
  evaluateIdempotentStart,
  foreignTenant,
  LOAD_TEST_PASSWORD,
  minimalWorkflowDefinition,
  planTenants,
  tenantForVu,
  tenantHeaders,
} from "../lib/tenancy.mjs";
import {
  tenancyFullOptions,
  tenancyOptionsFor,
  tenancySmokeOptions,
  TENANCY_SMOKE_THRESHOLDS,
  TENANCY_THRESHOLDS,
  thresholdsFromSlo,
  toThresholdExpression,
} from "../k6/lib/tenancy-scenarios.mjs";

test("tenant fixtures use a load-prefixed slug that cleanup queries can target", () => {
  const fixture = buildTenantFixture({ suffix: "run-1", index: 7 });
  assert.equal(fixture.slug, "load-run-1-7");
  assert.equal(fixture.adminEmail, "admin+run-1-7@loadtest.awap.dev");
  assert.equal(fixture.adminPassword, LOAD_TEST_PASSWORD);
  assert.equal(fixture.index, 7);
});

test("tenant fixture rejects a missing suffix or a bad index", () => {
  assert.throws(() => buildTenantFixture({ suffix: "", index: 0 }), TypeError);
  assert.throws(() => buildTenantFixture({ suffix: 5, index: 0 }), TypeError);
  assert.throws(() => buildTenantFixture({ suffix: "s", index: -1 }), TypeError);
  assert.throws(() => buildTenantFixture({ suffix: "s", index: 1.5 }), TypeError);
});

test("planTenants builds 1000+ unique fixtures and supports a start index", () => {
  const plan = planTenants({ suffix: "nightly-1", count: 1000 });
  assert.equal(plan.length, 1000);
  assert.equal(new Set(plan.map((fixture) => fixture.slug)).size, 1000);
  assert.equal(plan[999].slug, "load-nightly-1-999");

  const resumed = planTenants({ suffix: "nightly-1", count: 2, startIndex: 998 });
  assert.deepEqual(
    resumed.map((fixture) => fixture.slug),
    ["load-nightly-1-998", "load-nightly-1-999"],
  );
});

test("planTenants rejects invalid ranges", () => {
  assert.throws(() => planTenants({ suffix: "s", count: 0 }), TypeError);
  assert.throws(() => planTenants({ suffix: "s", count: 1, startIndex: -2 }), TypeError);
});

test("chunk keeps the concurrency plan visible and validated", () => {
  assert.deepEqual(chunk([1, 2, 3, 4, 5], 2), [[1, 2], [3, 4], [5]]);
  assert.deepEqual(chunk([], 4), []);
  assert.throws(() => chunk("nope", 2), TypeError);
  assert.throws(() => chunk([1], 0), TypeError);
});

test("tenantForVu pins VUs to tenants and wraps instead of returning undefined", () => {
  const tenants = [{ tenantId: "a" }, { tenantId: "b" }];
  assert.equal(tenantForVu(tenants, 1).tenantId, "a");
  assert.equal(tenantForVu(tenants, 2).tenantId, "b");
  assert.equal(tenantForVu(tenants, 3).tenantId, "a");
  assert.equal(tenantForVu(tenants, 0).tenantId, "a");
  assert.equal(tenantForVu(tenants, Number.NaN).tenantId, "a");
  assert.equal(tenantForVu([], 1), undefined);
  assert.equal(tenantForVu(undefined, 1), undefined);
});

test("foreignTenant returns the ring neighbour and refuses a single-tenant run", () => {
  const tenants = [{ tenantId: "a" }, { tenantId: "b" }, { tenantId: "c" }];
  assert.equal(foreignTenant(tenants, 1).tenantId, "b");
  assert.equal(foreignTenant(tenants, 3).tenantId, "a");
  assert.equal(foreignTenant([{ tenantId: "a" }], 1), undefined);
  assert.equal(foreignTenant([], 1), undefined);
  assert.equal(foreignTenant(tenants, 0).tenantId, "b");
  assert.equal(foreignTenant(tenants, Number.NaN).tenantId, "b");
});

test("foreignTenant never compares a tenant with a clone of itself", () => {
  // k6's SharedArray hands out a deep clone per access, so an identity-based lookup failed and the
  // isolation probe read the caller's own instance and reported a false cross-tenant leak.
  const tenants = [{ tenantId: "a" }, { tenantId: "b" }];
  for (let vu = 1; vu <= 4; vu += 1) {
    const own = structuredClone(tenantForVu(tenants, vu));
    const foreign = structuredClone(foreignTenant(tenants, vu));
    assert.notEqual(foreign.tenantId, own.tenantId, `VU ${vu} probed its own tenant`);
  }
});

test("tenantHeaders carry bearer, cookie and CSRF double-submit", () => {
  const headers = tenantHeaders({
    token: "tok",
    csrfToken: "csrf",
    tenantId: "tenant-1",
  });
  assert.equal(headers.Authorization, "Bearer tok");
  assert.equal(headers["X-Tenant-Id"], "tenant-1");
  assert.equal(headers["X-XSRF-TOKEN"], "csrf");
  assert.match(headers.Cookie, /awap_access_token=tok/);
  assert.match(headers.Cookie, /XSRF-TOKEN=csrf/);

  const withoutCsrf = tenantHeaders({ token: "tok", tenantId: "t" }, { "X-Extra": "1" });
  assert.equal(withoutCsrf["X-XSRF-TOKEN"], "");
  assert.ok(!withoutCsrf.Cookie.includes("XSRF-TOKEN"));
  assert.equal(withoutCsrf["X-Extra"], "1");

  const empty = tenantHeaders(undefined);
  assert.equal(empty.Authorization, "Bearer ");
  assert.equal(empty["X-Tenant-Id"], "");
});

test("classifyApprovalRace demands exactly one winner and counts unexpected statuses", () => {
  assert.deepEqual(classifyApprovalRace([200, 409, 409, 409, 409]).ok, true);
  assert.equal(
    classifyApprovalRace([200, 409, 409, 409, 409]).reason,
    "exactly-one-terminal-decision",
  );
  assert.equal(classifyApprovalRace([200]).ok, true);
  assert.equal(classifyApprovalRace([200, 200, 409]).reason, "multiple-approvals-succeeded:2");
  assert.equal(classifyApprovalRace([409, 409]).reason, "no-approval-succeeded");
  assert.equal(classifyApprovalRace([200, 409, 500]).reason, "unexpected-statuses:500");
  assert.deepEqual(classifyApprovalRace([]), {
    ok: false,
    reason: "no-approval-responses",
    approved: 0,
    conflicts: 0,
    unexpected: [],
  });
  assert.equal(classifyApprovalRace(undefined).ok, false);
});

test("evaluateIdempotentStart accepts a replay or an in-flight conflict, never a new id", () => {
  assert.deepEqual(
    evaluateIdempotentStart({ status: 201, id: "i-1" }, { status: 201, id: "i-1" }),
    {
      ok: true,
      reason: "replayed",
      instanceId: "i-1",
    },
  );
  assert.equal(evaluateIdempotentStart({ status: 201, id: "i-1" }, { status: 409 }).ok, true);
  assert.equal(
    evaluateIdempotentStart({ status: 201, id: "i-1" }, { status: 201, id: "i-2" }).reason,
    "second-request-diverged:201",
  );
  assert.equal(
    evaluateIdempotentStart({ status: 500 }, { status: 201 }).reason,
    "first-request-failed:500",
  );
  assert.equal(evaluateIdempotentStart(undefined, { status: 201 }).reason, "missing-response");
  assert.equal(
    evaluateIdempotentStart({ status: 201, id: "i-1" }, undefined).reason,
    "missing-response",
  );
});

test("assertOwnRows fails on foreign rows, missing tenant ids and non-array payloads", () => {
  assert.deepEqual(assertOwnRows([{ tenantId: "t" }, { tenantId: "t" }], "t"), {
    ok: true,
    reason: "all-rows-belong-to-the-caller",
    total: 2,
    foreign: 0,
    missing: 0,
  });
  assert.equal(assertOwnRows([{ tenantId: "other" }], "t").reason, "foreign-rows-visible:1");
  assert.equal(assertOwnRows([{ id: "x" }, null], "t").reason, "rows-without-tenant-id:2");
  assert.equal(assertOwnRows([{ id: "x" }], "x", "id").ok, true);
  assert.equal(assertOwnRows({ tenants: [] }, "t").reason, "response-is-not-an-array");
});

test("classifyCrossTenantProbe only accepts a denial", () => {
  assert.deepEqual(classifyCrossTenantProbe(404), { ok: true, reason: "denied", leak: false });
  assert.deepEqual(classifyCrossTenantProbe(403), { ok: true, reason: "denied", leak: false });
  assert.deepEqual(classifyCrossTenantProbe(200), {
    ok: false,
    reason: "cross-tenant-read-allowed:200",
    leak: true,
  });
  assert.deepEqual(classifyCrossTenantProbe(500), {
    ok: false,
    reason: "unexpected-status:500",
    leak: false,
  });
});

test("the minimal workflow definition is a valid start/end graph", () => {
  const definition = minimalWorkflowDefinition();
  assert.deepEqual(
    definition.nodes.map((node) => node.type),
    ["START", "END"],
  );
  assert.deepEqual(definition.transitions, [{ from: "start", to: "end" }]);
});

test("SLO checks become k6 threshold expressions", () => {
  assert.equal(
    toThresholdExpression({ stat: "p(95)", compare: "max", target: 2000 }),
    "p(95)<2000",
  );
  assert.equal(toThresholdExpression({ stat: "rate", compare: "max", target: 0.01 }), "rate<0.01");
  assert.equal(toThresholdExpression({ stat: "count", compare: "max", target: 0 }), "count==0");
  assert.equal(toThresholdExpression({ stat: "count", compare: "min", target: 1 }), "count>=1");
  assert.equal(toThresholdExpression({ stat: "rate", compare: "min", target: 20 }), "rate>=20");

  const thresholds = thresholdsFromSlo([
    { metric: "m", stat: "p(95)", compare: "max", target: 1 },
    { metric: "m", stat: "p(99)", compare: "max", target: 2 },
  ]);
  assert.deepEqual(thresholds, { m: ["p(95)<1", "p(99)<2"] });
});

test("nightly thresholds gate latency, errors and correctness counters", () => {
  assert.deepEqual(TENANCY_THRESHOLDS["http_req_duration{scenario:tenant_isolation}"], [
    "p(95)<2000",
    "p(99)<4000",
  ]);
  assert.deepEqual(TENANCY_THRESHOLDS["http_req_failed{scenario:approval_race}"], ["rate<0.01"]);
  assert.deepEqual(TENANCY_THRESHOLDS.isolation_violations, ["count==0"]);
  assert.deepEqual(TENANCY_THRESHOLDS.cross_tenant_leaks, ["count==0"]);
  assert.deepEqual(TENANCY_THRESHOLDS.approval_race_violations, ["count==0"]);
  assert.deepEqual(TENANCY_THRESHOLDS.idempotency_violations, ["count==0"]);
});

test("the full profile ramps to one VU per tenant and keeps every scenario", () => {
  const options = tenancyFullOptions(1000);
  assert.equal(options.scenarios.tenant_isolation.stages[1].target, 1000);
  assert.equal(options.scenarios.approval_race.vus, 50);
  assert.equal(options.scenarios.idempotent_start.vus, 50);
  assert.deepEqual(options.thresholds, TENANCY_THRESHOLDS);
  assert.ok(options.summaryTrendStats.includes("p(99)"), "p99 must be exported for the report");

  const tiny = tenancyFullOptions(1);
  assert.equal(tiny.scenarios.tenant_isolation.stages[1].target, 2);
  assert.equal(tiny.scenarios.approval_race.vus, 2);

  const capped = tenancyFullOptions(5000);
  assert.equal(capped.scenarios.tenant_isolation.stages[1].target, 1000);
});

test("the smoke profile stays short and low-VU", () => {
  const smoke = tenancySmokeOptions();
  assert.deepEqual(Object.keys(smoke.scenarios), [
    "tenant_isolation",
    "approval_race",
    "idempotent_start",
  ]);
  assert.equal(smoke.scenarios.tenant_isolation.vus, 3);
  assert.equal(smoke.thresholds, TENANCY_SMOKE_THRESHOLDS);
  assert.equal(smoke.thresholds.http_reqs, undefined, "smoke must not gate throughput");
  assert.deepEqual(TENANCY_THRESHOLDS.http_reqs, ["rate>=20"]);
});

test("tenancyOptionsFor selects the smoke profile only when requested", () => {
  assert.deepEqual(tenancyOptionsFor("1", 1000), tenancySmokeOptions());
  assert.deepEqual(tenancyOptionsFor("true", 1000), tenancySmokeOptions());
  assert.deepEqual(tenancyOptionsFor(undefined, 1000), tenancyFullOptions(1000));
});

test("the race uses more than one approver", () => {
  assert.ok(APPROVAL_RACE_ATTEMPTS >= 2);
});
