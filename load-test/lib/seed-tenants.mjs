/**
 * Bulk tenant seeding for the 1000+ tenant load test (issue #201).
 *
 * The CLI in `load-test/seed-tenants.mjs` is a thin wrapper: every branch lives here so
 * `node --test` can exercise dry runs, argument errors, retryable provider responses, partial
 * failures and the written credential file without a running stack.
 */
import { minimalWorkflowDefinition } from "./tenancy.mjs";

export const DEFAULT_TENANT_COUNT = 1000;
export const DEFAULT_CONCURRENCY = 16;
export const DEFAULT_OUTPUT = "load-test/tenants.json";

/** Invalid CLI usage: reported without a stack trace. */
export class UsageError extends Error {
  constructor(message) {
    super(message);
    this.name = "UsageError";
  }
}

/** A seeding step failed for one tenant; the message names the step and the status. */
export class SeedError extends Error {
  constructor(message) {
    super(message);
    this.name = "SeedError";
  }
}

export const USAGE = `usage: node load-test/seed-tenants.mjs [options]

  --tenants <n>        number of tenants to seed (default ${DEFAULT_TENANT_COUNT})
  --concurrency <n>    parallel seed workers (default ${DEFAULT_CONCURRENCY})
  --suffix <text>      slug suffix; defaults to <epoch-ms>-<pid>
  --start-index <n>    first fixture index, for resuming a partial run (default 0)
  --output <path>      credential file written for k6 (default ${DEFAULT_OUTPUT})
  --api-base <url>     backend base URL (default API_BASE or http://localhost:8080)
  --retries <n>        retries for 429/5xx/transport failures (default 3)
  --timeout <ms>       per-attempt request timeout (default 10000, 0 disables)
  --refresh            re-login every tenant in --output and rewrite fresh tokens
  --dry-run            validate the plan and print it without calling the API
  --help               print this help`;

function positiveInteger(flag, raw) {
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1) {
    throw new UsageError(`${flag} expects a positive integer, received "${raw}"`);
  }
  return value;
}

function nonNegativeInteger(flag, raw) {
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 0) {
    throw new UsageError(`${flag} expects a non-negative integer, received "${raw}"`);
  }
  return value;
}

function requiredValue(flag, raw) {
  if (raw === undefined || raw.startsWith("--")) {
    throw new UsageError(`${flag} expects a value`);
  }
  return raw;
}

/** Parses the seeder CLI. Unknown flags are usage errors so a typo cannot silently seed 1 tenant. */
export function parseSeedArgs(argv) {
  const options = {
    help: false,
    dryRun: false,
    refresh: false,
    count: DEFAULT_TENANT_COUNT,
    concurrency: DEFAULT_CONCURRENCY,
    suffix: null,
    startIndex: 0,
    output: DEFAULT_OUTPUT,
    apiBase: process.env.API_BASE ?? "http://localhost:8080",
    retries: 3,
    timeoutMs: 10_000,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    switch (arg) {
      case "--help":
        options.help = true;
        break;
      case "--dry-run":
        options.dryRun = true;
        break;
      case "--refresh":
        options.refresh = true;
        break;
      case "--tenants":
        index += 1;
        options.count = positiveInteger(arg, requiredValue(arg, argv[index]));
        break;
      case "--concurrency":
        index += 1;
        options.concurrency = positiveInteger(arg, requiredValue(arg, argv[index]));
        break;
      case "--start-index":
        index += 1;
        options.startIndex = nonNegativeInteger(arg, requiredValue(arg, argv[index]));
        break;
      case "--retries":
        index += 1;
        options.retries = nonNegativeInteger(arg, requiredValue(arg, argv[index]));
        break;
      case "--suffix":
        index += 1;
        options.suffix = requiredValue(arg, argv[index]);
        break;
      case "--output":
        index += 1;
        options.output = requiredValue(arg, argv[index]);
        break;
      case "--api-base":
        index += 1;
        options.apiBase = requiredValue(arg, argv[index]);
        break;
      case "--timeout":
        index += 1;
        options.timeoutMs = Number(nonNegativeValue(arg, requiredValue(arg, argv[index])));
        break;
      default:
        throw new UsageError(`unknown argument "${arg}"`);
    }
  }

  return options;
}

/** `awap_access_token` + session marker; CSRF is added once the token is bootstrapped. */
export function cookieHeader(token, csrfToken) {
  const cookies = [`awap_access_token=${encodeURIComponent(token)}`, "awap_session=1"];
  if (csrfToken) {
    cookies.push(`XSRF-TOKEN=${encodeURIComponent(csrfToken)}`);
  }
  return cookies.join("; ");
}

/** Extracts and decodes one Set-Cookie value. */
export function cookieValue(setCookies, name) {
  for (const cookie of setCookies ?? []) {
    const [pair] = cookie.split(";");
    const separator = pair.indexOf("=");
    if (separator > 0 && pair.slice(0, separator).trim() === name) {
      return decodeURIComponent(pair.slice(separator + 1).trim());
    }
  }
  return "";
}

/** Any non-negative finite integer; `--timeout 0` disables the per-attempt deadline. */
function nonNegativeValue(flag, raw) {
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0) {
    throw new UsageError(`${flag} expects a non-negative number, received "${raw}"`);
  }
  return value;
}

function retryDelayMs(response, attempt, baseMs) {
  const headers = response?.headers ?? {};
  const header = headers["retry-after"] ?? headers["Retry-After"];
  const seconds = Number.parseInt(header ?? "", 10);
  if (Number.isInteger(seconds) && seconds >= 0) {
    return seconds * 1000;
  }
  return baseMs * 2 ** (attempt - 1);
}

/**
 * Minimal JSON client with bounded retries. Only 429/5xx/transport failures retry: a 400/401/409 is
 * a deterministic answer and retrying it would multiply a broken seed run.
 */
export function createHttpClient({
  base,
  fetchImpl = globalThis.fetch,
  retries = 3,
  retryBaseMs = 250,
  timeoutMs = 10_000,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
}) {
  async function request(method, path, { body, headers } = {}) {
    let lastTransportError;
    for (let attempt = 1; attempt <= retries + 1; attempt += 1) {
      let response;
      try {
        response = await fetchImpl(`${base}${path}`, {
          method,
          headers,
          body: body === undefined ? undefined : JSON.stringify(body),
          // A stalled keep-alive socket (observed as an occasional 60s login) would otherwise
          // stall the whole seed run; aborting and retrying is safe because seeding is idempotent
          // per unique slug and a retried login simply issues another token.
          signal: timeoutMs > 0 ? AbortSignal.timeout(timeoutMs) : undefined,
        });
      } catch (error) {
        lastTransportError = error;
        if (attempt > retries) {
          break;
        }
        await sleep(retryBaseMs * 2 ** (attempt - 1));
        continue;
      }
      const parsed = await response.json().catch(() => ({}));
      const result = {
        status: response.status,
        body: parsed,
        // Lower-cased keys so Retry-After is readable regardless of the client's casing.
        headers: Object.fromEntries(
          (response.headers.entries?.() ?? []).map(([name, value]) => [
            String(name).toLowerCase(),
            value,
          ]),
        ),
        setCookies: response.headers.getSetCookie?.() ?? [],
      };
      const retryable = result.status === 429 || result.status >= 500;
      if (retryable && attempt <= retries) {
        await sleep(retryDelayMs(result, attempt, retryBaseMs));
        continue;
      }
      return result;
    }
    throw new SeedError(
      `${method} ${path} failed after ${retries} retries: ${lastTransportError?.message ?? "no response"}`,
    );
  }

  return { request, base, retries };
}

function expectStatus(step, slug, response, allowed) {
  if (!allowed.includes(response.status)) {
    throw new SeedError(
      `${step} for ${slug} returned ${response.status}: ${JSON.stringify(response.body)}`,
    );
  }
}

/**
 * Seeds one tenant end to end: tenant, admin login, CSRF, one published workflow definition and one
 * started instance. The instance id is what the isolation scenario probes cross-tenant.
 */
export async function seedTenant(client, fixture) {
  const registration = await client.request("POST", "/api/v1/tenants", {
    headers: { "Content-Type": "application/json" },
    body: {
      slug: fixture.slug,
      name: fixture.name,
      adminEmail: fixture.adminEmail,
      adminPassword: fixture.adminPassword,
      adminFullName: fixture.adminFullName,
    },
  });
  expectStatus("tenant registration", fixture.slug, registration, [201]);

  const login = await client.request("POST", "/api/v1/auth/login", {
    headers: { "Content-Type": "application/json" },
    body: {
      tenantSlug: fixture.slug,
      email: fixture.adminEmail,
      password: fixture.adminPassword,
    },
  });
  expectStatus("admin login", fixture.slug, login, [200]);

  const token = cookieValue(login.setCookies, "awap_access_token") || login.body?.accessToken || "";
  if (!token) {
    throw new SeedError(`admin login for ${fixture.slug} issued no access token`);
  }

  // The SPA bootstraps the CSRF cookie before every state-changing call; a scripted client must do
  // the same or workflow starts answer 403.
  const csrf = await client.request("GET", "/api/v1/auth/csrf", {
    headers: { Cookie: cookieHeader(token) },
  });
  const csrfToken = csrf.body?.token || cookieValue(csrf.setCookies, "XSRF-TOKEN");
  if (!csrfToken) {
    throw new SeedError(
      `CSRF bootstrap for ${fixture.slug} returned no token (status ${csrf.status})`,
    );
  }

  const auth = {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
    Cookie: cookieHeader(token, csrfToken),
    "X-XSRF-TOKEN": csrfToken,
  };
  const me = await client.request("GET", "/api/v1/auth/me", { headers: auth });
  expectStatus("profile lookup", fixture.slug, me, [200]);
  const tenantId = me.body?.tenantId ?? "";
  const userId = me.body?.id ?? "";
  if (!tenantId || !userId) {
    throw new SeedError(`profile lookup for ${fixture.slug} returned no tenantId/userId`);
  }
  const headers = { ...auth, "X-Tenant-Id": tenantId };

  const definition = await client.request("POST", "/api/v1/workflow-definitions", {
    headers,
    body: {
      name: `Tenancy load test ${fixture.index}`,
      description: "Created by load-test/seed-tenants.mjs for the 1000+ tenant suite.",
      definitionJson: JSON.stringify(minimalWorkflowDefinition()),
    },
  });
  expectStatus("workflow definition create", fixture.slug, definition, [201]);
  const workflowDefinitionId = definition.body?.id ?? "";
  if (!workflowDefinitionId) {
    throw new SeedError(`workflow definition create for ${fixture.slug} returned no id`);
  }

  const publish = await client.request(
    "POST",
    `/api/v1/workflow-definitions/${workflowDefinitionId}/publish`,
    { headers, body: {} },
  );
  expectStatus("workflow definition publish", fixture.slug, publish, [200, 201]);

  const start = await client.request("POST", "/api/v1/workflow-instances", {
    headers: { ...headers, "Idempotency-Key": `seed-${fixture.slug}` },
    body: { workflowDefinitionId, idempotencyKey: `seed-${fixture.slug}`, contextJson: "{}" },
  });
  expectStatus("workflow instance start", fixture.slug, start, [200, 201]);
  const workflowInstanceId = start.body?.id ?? "";
  if (!workflowInstanceId) {
    throw new SeedError(`workflow instance start for ${fixture.slug} returned no id`);
  }

  return {
    index: fixture.index,
    slug: fixture.slug,
    email: fixture.adminEmail,
    password: fixture.adminPassword,
    token,
    csrfToken,
    tenantId,
    userId,
    workflowDefinitionId,
    workflowInstanceId,
  };
}

/**
 * Logs an already-seeded tenant in again and rebuilds its CSRF pair. Access tokens live 15 minutes
 * by default, while seeding 1000 tenants can take longer; the k6 run must start with fresh tokens.
 * Registration, definitions and the instance id are reused so nothing is duplicated.
 */
export async function refreshTenant(client, tenant) {
  const login = await client.request("POST", "/api/v1/auth/login", {
    headers: { "Content-Type": "application/json" },
    body: {
      tenantSlug: tenant.slug,
      email: tenant.email,
      password: tenant.password,
    },
  });
  expectStatus("admin login", tenant.slug, login, [200]);
  const token = cookieValue(login.setCookies, "awap_access_token") || login.body?.accessToken || "";
  if (!token) {
    throw new SeedError(`admin login for ${tenant.slug} issued no access token`);
  }
  const csrf = await client.request("GET", "/api/v1/auth/csrf", {
    headers: { Cookie: cookieHeader(token) },
  });
  const csrfToken = csrf.body?.token || cookieValue(csrf.setCookies, "XSRF-TOKEN");
  if (!csrfToken) {
    throw new SeedError(
      `CSRF bootstrap for ${tenant.slug} returned no token (status ${csrf.status})`,
    );
  }
  return { ...tenant, token, csrfToken };
}

/** Refreshes every stored credential, reusing the same worker-pool semantics as seeding. */
export async function refreshTenants({
  client,
  tenants,
  concurrency,
  maxFailures = 5,
  log = () => {},
}) {
  const { results, failures } = await runWithConcurrency({
    items: tenants,
    concurrency,
    maxFailures,
    worker: async (tenant) => {
      const refreshed = await refreshTenant(client, tenant);
      log(`refreshed ${refreshed.slug}`);
      return refreshed;
    },
  });
  return {
    tenants: results.sort((left, right) => left.index - right.index),
    failures: failures.map((failure) => ({
      slug: failure.item.slug,
      message: failure.error?.message ?? String(failure.error),
    })),
    unprocessed: tenants.length - results.length - failures.length,
  };
}

/**
 * Runs `worker` over `items` with a fixed worker pool. Scheduling stops after `maxFailures` so a
 * broken stack is not hammered with another 900 registrations, but the failures already collected
 * are reported in full.
 */
export async function runWithConcurrency({
  items,
  concurrency,
  worker,
  maxFailures = Number.POSITIVE_INFINITY,
}) {
  const queue = [...items];
  const results = [];
  const failures = [];
  const workerCount = Math.max(1, Math.min(concurrency, queue.length));

  async function drain() {
    while (queue.length > 0 && failures.length < maxFailures) {
      const item = queue.shift();
      try {
        results.push(await worker(item));
      } catch (error) {
        failures.push({ item, error });
      }
    }
  }

  await Promise.all(Array.from({ length: workerCount }, drain));
  return { results, failures };
}

/** Seeds every fixture and reports partial results; callers decide the exit code. */
export async function seedTenants({
  client,
  fixtures,
  concurrency,
  maxFailures = 5,
  log = () => {},
}) {
  const { results, failures } = await runWithConcurrency({
    items: fixtures,
    concurrency,
    maxFailures,
    worker: async (fixture) => {
      const tenant = await seedTenant(client, fixture);
      log(`seeded ${tenant.slug}`);
      return tenant;
    },
  });

  return {
    seeded: results.sort((left, right) => left.index - right.index),
    failures: failures.map((failure) => ({
      slug: failure.item.slug,
      message: failure.error?.message ?? String(failure.error),
    })),
    unprocessed: fixtures.length - results.length - failures.length,
  };
}
