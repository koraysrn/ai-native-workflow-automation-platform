/**
 * Pure tenancy model shared by the seeder, the k6 script and the unit tests (issue #201).
 *
 * Everything here is deterministic and side-effect free so every branch can be exercised by
 * `node --test` without a running stack. The k6 script imports the same helpers, which keeps the
 * isolation/race assertions identical to the ones the tests verify.
 */

/** Password used for every seeded admin. The load-test database is throwaway. */
export const LOAD_TEST_PASSWORD = "Load-Test-123!";

/** Number of concurrent approvers used by the race scenario (one may win, the rest conflict). */
export const APPROVAL_RACE_ATTEMPTS = 5;

/** Minimal published graph: START -> END, enough to start an instance per tenant. */
export function minimalWorkflowDefinition() {
  return {
    nodes: [
      { id: "start", type: "START", name: "Start" },
      { id: "end", type: "END", name: "End" },
    ],
    transitions: [{ from: "start", to: "end" }],
  };
}

/**
 * One tenant fixture. Slugs are prefixed with `load-` so the cleanup queries in
 * `load-test/README.md` can target every fixture without touching real data.
 */
export function buildTenantFixture({ suffix, index }) {
  if (typeof suffix !== "string" || suffix.length === 0) {
    throw new TypeError("suffix must be a non-empty string");
  }
  if (!Number.isInteger(index) || index < 0) {
    throw new TypeError("index must be a non-negative integer");
  }
  return {
    index,
    slug: `load-${suffix}-${index}`,
    name: `Load Tenant ${index}`,
    adminEmail: `admin+${suffix}-${index}@loadtest.awap.dev`,
    adminPassword: LOAD_TEST_PASSWORD,
    adminFullName: `Load Admin ${index}`,
  };
}

/** Builds the full fixture list; the seeder never invents slugs outside this function. */
export function planTenants({ suffix, count, startIndex = 0 }) {
  if (!Number.isInteger(count) || count < 1) {
    throw new TypeError("count must be a positive integer");
  }
  if (!Number.isInteger(startIndex) || startIndex < 0) {
    throw new TypeError("startIndex must be a non-negative integer");
  }
  return Array.from({ length: count }, (_, offset) =>
    buildTenantFixture({ suffix, index: startIndex + offset }),
  );
}

/** Splits work into fixed-size batches so dry runs can report the planned concurrency. */
export function chunk(items, size) {
  if (!Array.isArray(items)) {
    throw new TypeError("items must be an array");
  }
  if (!Number.isInteger(size) || size < 1) {
    throw new TypeError("size must be a positive integer");
  }
  const batches = [];
  for (let index = 0; index < items.length; index += size) {
    batches.push(items.slice(index, index + size));
  }
  return batches;
}

/**
 * Round-robin assignment from a k6 VU id to a tenant. VU ids start at 1, so VU 1 maps to the first
 * tenant; VUs beyond the tenant count wrap instead of returning undefined and running unauthenticated.
 */
export function tenantForVu(tenants, vu) {
  if (!Array.isArray(tenants) || tenants.length === 0) {
    return undefined;
  }
  const position = Number.isFinite(vu) ? Math.max(0, Math.trunc(vu) - 1) : 0;
  return tenants[position % tenants.length];
}

/**
 * The next tenant in the ring: the cross-tenant probe target. Undefined for a single tenant.
 *
 * The index is derived arithmetically because k6's SharedArray returns a clone on every access, so
 * `indexOf(tenantForVu(...))` was -1 and the probe compared tenant A with itself (a false leak).
 */
export function foreignTenant(tenants, vu) {
  if (!Array.isArray(tenants) || tenants.length < 2) {
    return undefined;
  }
  const position = Number.isFinite(vu) ? Math.max(0, Math.trunc(vu) - 1) : 0;
  return tenants[(position + 1) % tenants.length];
}

/** Browser-shaped headers: bearer + access cookie + the CSRF double-submit pair. */
export function tenantHeaders(tenant, extra = {}) {
  const token = tenant?.token ?? "";
  const csrfToken = tenant?.csrfToken ?? "";
  const cookies = [`awap_access_token=${encodeURIComponent(token)}`, "awap_session=1"];
  if (csrfToken) {
    cookies.push(`XSRF-TOKEN=${encodeURIComponent(csrfToken)}`);
  }
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
    Cookie: cookies.join("; "),
    "X-XSRF-TOKEN": csrfToken,
    "X-Tenant-Id": tenant?.tenantId ?? "",
    ...extra,
  };
}

/**
 * A double approval must produce exactly one success and a conflict for every other attempt.
 * A 403 or 500 is a defect, not a "lost" race, so it is reported separately.
 */
export function classifyApprovalRace(statuses) {
  if (!Array.isArray(statuses) || statuses.length === 0) {
    return {
      ok: false,
      reason: "no-approval-responses",
      approved: 0,
      conflicts: 0,
      unexpected: [],
    };
  }
  const approved = statuses.filter((status) => status === 200).length;
  const conflicts = statuses.filter((status) => status === 409).length;
  const unexpected = statuses.filter((status) => status !== 200 && status !== 409);
  const ok = approved === 1 && conflicts === statuses.length - 1 && unexpected.length === 0;
  let reason = "exactly-one-terminal-decision";
  if (approved === 0) {
    reason = "no-approval-succeeded";
  } else if (approved > 1) {
    reason = `multiple-approvals-succeeded:${approved}`;
  } else if (unexpected.length > 0) {
    reason = `unexpected-statuses:${unexpected.join(",")}`;
  }
  return { ok, reason, approved, conflicts, unexpected };
}

/**
 * Replaying the same Idempotency-Key must not create a second instance. The response is valid when
 * the API replays the first response, or answers 409 while the first request is still in flight.
 */
export function evaluateIdempotentStart(first, second) {
  if (!first || !second) {
    return { ok: false, reason: "missing-response" };
  }
  if (first.status !== 201 && first.status !== 200) {
    return { ok: false, reason: `first-request-failed:${first.status}` };
  }
  if (second.status === 409) {
    return { ok: true, reason: "in-flight-conflict", instanceId: first.id };
  }
  if (second.status === first.status && second.id && second.id === first.id) {
    return { ok: true, reason: "replayed", instanceId: first.id };
  }
  return {
    ok: false,
    reason: `second-request-diverged:${second.status}`,
    instanceId: first.id,
    secondId: second.id,
  };
}

/**
 * Tenant B must never see tenant A's rows. Every row of the caller's own list is checked, and a
 * missing tenant field counts as a failure: silently accepting it would hide a serialization change
 * that removed the isolation signal.
 */
export function assertOwnRows(rows, tenantId, field = "tenantId") {
  if (!Array.isArray(rows)) {
    return { ok: false, reason: "response-is-not-an-array", total: 0, foreign: 0, missing: 0 };
  }
  let foreign = 0;
  let missing = 0;
  for (const row of rows) {
    if (!row || typeof row !== "object" || row[field] === undefined || row[field] === null) {
      missing += 1;
      continue;
    }
    if (String(row[field]) !== String(tenantId)) {
      foreign += 1;
    }
  }
  const ok = foreign === 0 && missing === 0;
  let reason = "all-rows-belong-to-the-caller";
  if (foreign > 0) {
    reason = `foreign-rows-visible:${foreign}`;
  } else if (missing > 0) {
    reason = `rows-without-tenant-id:${missing}`;
  }
  return { ok, reason, total: rows.length, foreign, missing };
}

/** A cross-tenant read is safe only when the API answers 403/404; anything else is a leak. */
export function classifyCrossTenantProbe(status) {
  if (status === 403 || status === 404) {
    return { ok: true, reason: "denied", leak: false };
  }
  if (status >= 200 && status < 300) {
    return { ok: false, reason: `cross-tenant-read-allowed:${status}`, leak: true };
  }
  return { ok: false, reason: `unexpected-status:${status}`, leak: false };
}
