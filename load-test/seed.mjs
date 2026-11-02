// Seeds a load-test tenant and captures the ids/token the k6 scenarios need.
// Unique slug per run keeps repeated runs idempotent and leaves no duplicate fixtures.
//
// Usage:
//   node load-test/seed.mjs                 # one tenant (default)
//   node load-test/seed.mjs 20              # N tenants, all unique
//   API_BASE=http://localhost:8080 node load-test/seed.mjs
import { writeFile } from "node:fs/promises";

const base = process.env.API_BASE ?? "http://localhost:8080";
const count = Number.parseInt(process.argv[2] ?? "1", 10);
const suffix = process.env.LOAD_TEST_SUFFIX ?? `${Date.now()}-${process.pid}`;

async function jsonRequest(path, init = {}) {
  const response = await fetch(`${base}${path}`, init);
  const body = await response.json().catch(() => ({}));
  return {
    status: response.status,
    body,
    setCookies: response.headers.getSetCookie?.() ?? [],
  };
}

/** Extracts and decodes one Set-Cookie value; the auth endpoints issue HttpOnly cookies. */
function cookieValue(setCookies, name) {
  for (const cookie of setCookies) {
    const [pair] = cookie.split(";");
    const separator = pair.indexOf("=");
    if (separator > 0 && pair.slice(0, separator).trim() === name) {
      return decodeURIComponent(pair.slice(separator + 1).trim());
    }
  }
  return "";
}

/** A browser-shaped cookie header for the scripted API client. */
function cookieHeader(token, csrfToken) {
  const cookies = [`awap_access_token=${encodeURIComponent(token)}`, "awap_session=1"];
  if (csrfToken) {
    cookies.push(`XSRF-TOKEN=${encodeURIComponent(csrfToken)}`);
  }
  return cookies.join("; ");
}

async function seedTenant(index) {
  const slug = `load-${suffix}-${index}`;
  const email = `admin+${suffix}-${index}@loadtest.awap.dev`;
  const password = "Load-Test-123!";

  const registration = await jsonRequest("/api/v1/tenants", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      slug,
      name: `Load Tenant ${index}`,
      adminEmail: email,
      adminPassword: password,
      adminFullName: `Load Admin ${index}`,
    }),
  });
  if (registration.status !== 201) {
    throw new Error(`Seeding tenant ${slug} failed with status ${registration.status}`);
  }

  const login = await jsonRequest("/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ tenantSlug: slug, email, password }),
  });
  if (login.status !== 200) {
    throw new Error(`Login for ${slug} failed with status ${login.status}`);
  }
  const token =
    cookieValue(login.setCookies, "awap_access_token") || login.body.accessToken || "";
  if (!token) {
    throw new Error(`Login for ${slug} succeeded but no access-token cookie was issued`);
  }

  // The SPA bootstraps the CSRF cookie through this endpoint before a state-changing request; a
  // scripted client must do the same or workflow starts are rejected with 403.
  const csrf = await jsonRequest("/api/v1/auth/csrf", {
    headers: { Cookie: cookieHeader(token) },
  });
  const csrfToken = csrf.body.token || cookieValue(csrf.setCookies, "XSRF-TOKEN");
  if (!csrfToken) {
    throw new Error(`CSRF bootstrap for ${slug} returned no token`);
  }

  const auth = {
    Authorization: `Bearer ${token}`,
    Cookie: cookieHeader(token, csrfToken),
    "X-XSRF-TOKEN": csrfToken,
  };
  const memberships = await jsonRequest("/api/v1/auth/tenants", { headers: auth });
  const tenantId = Array.isArray(memberships.body)
    ? (memberships.body.find((item) => item?.role)?.tenantId ?? "")
    : "";

  const headers = { ...auth, "X-Tenant-Id": tenantId };
  const definitions = await jsonRequest("/api/v1/workflow-definitions", { headers });
  const workflowDefinitionId = Array.isArray(definitions.body)
    ? (definitions.body[0]?.id ?? "")
    : "";
  const documents = await jsonRequest("/api/v1/documents", { headers });
  const documentId = Array.isArray(documents.body) ? (documents.body[0]?.id ?? "") : "";

  return {
    slug,
    email,
    password,
    token,
    csrfToken,
    tenantId,
    workflowDefinitionId,
    documentId,
  };
}

const seeded = [];
for (let index = 0; index < count; index += 1) {
  seeded.push(await seedTenant(index));
}

const primary = seeded[0];
await writeFile(
  new URL("./seed.json", import.meta.url),
  JSON.stringify({ suffix, tenants: seeded }, null, 2),
  "utf8",
);

// GitHub Actions consumes these through $GITHUB_ENV.
const exports = {
  TENANT_ID: primary.tenantId,
  ACCESS_TOKEN: primary.token,
  CSRF_TOKEN: primary.csrfToken,
  TENANT_SLUG: primary.slug,
  WORKFLOW_DEFINITION_ID: primary.workflowDefinitionId,
  DOCUMENT_ID: primary.documentId,
  LOAD_TEST_SUFFIX: suffix,
};
if (process.env.GITHUB_ENV) {
  const lines = Object.entries(exports).map(([key, value]) => `${key}=${value ?? ""}`);
  await writeFile(process.env.GITHUB_ENV, `${lines.join("\n")}\n`, { flag: "a" });
}

console.log(`Seeded ${seeded.length} tenant(s) with suffix ${suffix}.`);
