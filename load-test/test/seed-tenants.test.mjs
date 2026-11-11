import assert from "node:assert/strict";
import { test } from "node:test";
import {
  cookieHeader,
  cookieValue,
  createHttpClient,
  parseSeedArgs,
  refreshTenant,
  refreshTenants,
  runWithConcurrency,
  SeedError,
  seedTenant,
  seedTenants,
  UsageError,
} from "../lib/seed-tenants.mjs";
import { main } from "../seed-tenants.mjs";
import { main as reportMain } from "../report-slo.mjs";
import { buildTenantFixture } from "../lib/tenancy.mjs";

/** Minimal fetch Response stand-in with the parts the client reads. */
function response(status, body = {}, { cookies = [], headers = {} } = {}) {
  return {
    status,
    body,
    setCookies: cookies,
    json: async () => body,
    headers: {
      getSetCookie: () => cookies,
      entries: () => Object.entries(headers),
    },
  };
}

/** Ordered response queue plus a call log, so request shapes can be asserted. */
function sequenceClient(responses) {
  const calls = [];
  let index = 0;
  return {
    calls,
    async request(method, path, init = {}) {
      calls.push({ method, path, init });
      const next = responses[index];
      index += 1;
      if (next === undefined) {
        throw new Error(`unexpected request ${method} ${path}`);
      }
      if (next instanceof Error) {
        throw next;
      }
      return next;
    },
  };
}

/** Stateful fake backend that seeds every valid slug through the happy path. */
function fakeBackend({ failSlugs = new Set(), loginBody = { accessToken: "token-1" } } = {}) {
  const calls = [];
  const client = {
    calls,
    async request(method, path, init = {}) {
      calls.push({ method, path, init });
      if (path === "/api/v1/tenants") {
        return failSlugs.has(init.body.slug)
          ? response(400, { detail: "rejected" })
          : response(201, {});
      }
      if (path === "/api/v1/auth/login") {
        return response(200, loginBody, {
          cookies: ["awap_access_token=cookie-token; Path=/; HttpOnly"],
        });
      }
      if (path === "/api/v1/auth/csrf") {
        return response(200, { token: "csrf-1" });
      }
      if (path === "/api/v1/auth/me") {
        return response(200, { id: "user-1", tenantId: "tenant-1" });
      }
      if (path === "/api/v1/workflow-definitions") {
        return response(201, { id: "definition-1" });
      }
      if (path.endsWith("/publish")) {
        return response(200, { id: "definition-1" });
      }
      if (path === "/api/v1/workflow-instances") {
        return response(201, { id: "instance-1" });
      }
      throw new Error(`unexpected path ${path}`);
    },
  };
  return client;
}

const HAPPY_RESPONSES = () => [
  response(201, {}),
  response(200, { accessToken: "token-1" }),
  response(200, { token: "csrf-1" }),
  response(200, { id: "user-1", tenantId: "tenant-1" }),
  response(201, { id: "definition-1" }),
  response(200, { id: "definition-1" }),
  response(201, { id: "instance-1" }),
];

const FIXTURE = buildTenantFixture({ suffix: "unit", index: 0 });

test("parseSeedArgs defaults to a 1000-tenant grid", () => {
  const options = parseSeedArgs([]);
  assert.equal(options.count, 1000);
  assert.equal(options.concurrency, 16);
  assert.equal(options.retries, 3);
  assert.equal(options.output, "load-test/tenants.json");
  assert.equal(options.dryRun, false);
  assert.equal(options.suffix, null);
  assert.equal(options.startIndex, 0);
});

test("parseSeedArgs accepts every supported flag", () => {
  const options = parseSeedArgs([
    "--tenants",
    "1200",
    "--concurrency",
    "32",
    "--suffix",
    "nightly-7",
    "--start-index",
    "1000",
    "--output",
    "/tmp/tenants.json",
    "--api-base",
    "http://api:8080",
    "--retries",
    "0",
    "--dry-run",
  ]);
  assert.deepEqual(
    {
      count: options.count,
      concurrency: options.concurrency,
      suffix: options.suffix,
      startIndex: options.startIndex,
      output: options.output,
      apiBase: options.apiBase,
      retries: options.retries,
      dryRun: options.dryRun,
    },
    {
      count: 1200,
      concurrency: 32,
      suffix: "nightly-7",
      startIndex: 1000,
      output: "/tmp/tenants.json",
      apiBase: "http://api:8080",
      retries: 0,
      dryRun: true,
    },
  );
});

test("parseSeedArgs rejects typos and invalid numbers instead of seeding silently", () => {
  assert.throws(() => parseSeedArgs(["--tenants", "many"]), UsageError);
  assert.throws(() => parseSeedArgs(["--tenants", "0"]), UsageError);
  assert.throws(() => parseSeedArgs(["--tenants"]), UsageError);
  assert.throws(() => parseSeedArgs(["--tenants", "--dry-run"]), UsageError);
  assert.throws(() => parseSeedArgs(["--start-index", "-1"]), UsageError);
  assert.throws(() => parseSeedArgs(["--concurrency", "1.5"]), UsageError);
  assert.throws(() => parseSeedArgs(["--publish"]), UsageError);
  assert.throws(() => parseSeedArgs(["--timeout", "-5"]), UsageError);
  assert.throws(() => parseSeedArgs(["--timeout", "soon"]), UsageError);
  assert.equal(parseSeedArgs(["--timeout", "0"]).timeoutMs, 0);
  assert.equal(parseSeedArgs(["--help"]).help, true);
  assert.equal(parseSeedArgs([]).timeoutMs, 10_000);
});

test("cookie helpers read and build the browser cookie header", () => {
  assert.equal(cookieHeader("tok"), "awap_access_token=tok; awap_session=1");
  assert.equal(
    cookieHeader("tok", "csrf"),
    "awap_access_token=tok; awap_session=1; XSRF-TOKEN=csrf",
  );
  assert.equal(cookieValue(["awap_access_token=a%20b; Path=/"], "awap_access_token"), "a b");
  assert.equal(cookieValue(["other=1"], "awap_access_token"), "");
  assert.equal(cookieValue(["garbage"], "awap_access_token"), "");
  assert.equal(cookieValue(undefined, "awap_access_token"), "");
});

test("createHttpClient parses cookies, headers and bodies", async () => {
  const fetchImpl = async () =>
    response(200, { ok: true }, { cookies: ["a=1"], headers: { "Retry-After": "1" } });
  const client = createHttpClient({ base: "http://api", fetchImpl, retries: 0 });
  const result = await client.request("GET", "/health");
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, { ok: true });
  assert.deepEqual(result.setCookies, ["a=1"]);
  assert.equal(result.headers["retry-after"], "1");
  assert.equal(client.base, "http://api");
});

test("createHttpClient retries 5xx with exponential backoff", async () => {
  const sleeps = [];
  let call = 0;
  const fetchImpl = async () => {
    call += 1;
    return call === 1 ? response(500, {}) : response(200, { ok: true });
  };
  const client = createHttpClient({
    base: "http://api",
    fetchImpl,
    retries: 2,
    retryBaseMs: 10,
    sleep: async (ms) => sleeps.push(ms),
  });
  const result = await client.request("GET", "/x");
  assert.equal(result.status, 200);
  assert.deepEqual(sleeps, [10]);
});

test("createHttpClient honours Retry-After on 429", async () => {
  const sleeps = [];
  let call = 0;
  const fetchImpl = async () => {
    call += 1;
    return call === 1 ? response(429, {}, { headers: { "Retry-After": "2" } }) : response(200, {});
  };
  const client = createHttpClient({
    base: "http://api",
    fetchImpl,
    retries: 1,
    sleep: async (ms) => sleeps.push(ms),
  });
  await client.request("GET", "/x");
  assert.deepEqual(sleeps, [2000]);
});

test("createHttpClient returns the last retryable response once the budget is spent", async () => {
  const sleeps = [];
  const fetchImpl = async () => response(503, { detail: "unavailable" });
  const client = createHttpClient({
    base: "http://api",
    fetchImpl,
    retries: 1,
    sleep: async (ms) => sleeps.push(ms),
  });
  const result = await client.request("GET", "/x");
  assert.equal(result.status, 503);
  assert.deepEqual(sleeps, [250]);
});

test("createHttpClient never retries a 4xx", async () => {
  const sleeps = [];
  const fetchImpl = async () => response(400, {});
  const client = createHttpClient({
    base: "http://api",
    fetchImpl,
    retries: 3,
    sleep: async (ms) => sleeps.push(ms),
  });
  assert.equal((await client.request("GET", "/x")).status, 400);
  assert.deepEqual(sleeps, []);
});

test("createHttpClient retries transport failures and then gives up with SeedError", async () => {
  const sleeps = [];
  let call = 0;
  const flaky = async () => {
    call += 1;
    if (call === 1) throw new Error("ECONNRESET");
    return response(200, {});
  };
  const recovered = createHttpClient({
    base: "http://api",
    fetchImpl: flaky,
    retries: 1,
    sleep: async (ms) => sleeps.push(ms),
  });
  assert.equal((await recovered.request("GET", "/x")).status, 200);
  assert.deepEqual(sleeps, [250]);

  const broken = createHttpClient({
    base: "http://api",
    fetchImpl: async () => {
      throw new Error("ECONNREFUSED");
    },
    retries: 1,
    sleep: async () => {},
  });
  await assert.rejects(
    () => broken.request("GET", "/x"),
    (error) => {
      assert.ok(error instanceof SeedError);
      assert.match(error.message, /ECONNREFUSED/);
      return true;
    },
  );
});

test("createHttpClient bounds each attempt with a timeout signal and retries an abort", async () => {
  const signals = [];
  let call = 0;
  const fetchImpl = async (url, init) => {
    signals.push(init.signal);
    call += 1;
    if (call === 1) {
      const abort = new Error("This operation was aborted");
      abort.name = "TimeoutError";
      throw abort;
    }
    return response(200, {});
  };
  const client = createHttpClient({
    base: "http://api",
    fetchImpl,
    retries: 1,
    retryBaseMs: 1,
    timeoutMs: 250,
    sleep: async () => {},
  });
  assert.equal((await client.request("GET", "/slow")).status, 200);
  assert.equal(
    typeof signals[0]?.aborted,
    "boolean",
    "a bounded attempt must carry an AbortSignal",
  );

  const unbounded = createHttpClient({
    base: "http://api",
    fetchImpl: async (url, init) => {
      signals.push(init.signal);
      return response(200, {});
    },
    retries: 0,
    timeoutMs: 0,
  });
  await unbounded.request("GET", "/fast");
  assert.equal(signals.at(-1), undefined);
});

test("createHttpClient tolerates a non-JSON body", async () => {
  const fetchImpl = async () => ({
    status: 204,
    json: async () => {
      throw new Error("no body");
    },
    headers: { getSetCookie: () => [], entries: () => [] },
  });
  const client = createHttpClient({ base: "http://api", fetchImpl, retries: 0 });
  assert.deepEqual((await client.request("DELETE", "/x")).body, {});
});

test("seedTenant seeds a tenant, a published definition and one instance", async () => {
  const client = sequenceClient(HAPPY_RESPONSES());
  const tenant = await seedTenant(client, FIXTURE);
  assert.deepEqual(tenant, {
    index: 0,
    slug: "load-unit-0",
    email: "admin+unit-0@loadtest.awap.dev",
    password: tenant.password,
    token: "token-1",
    csrfToken: "csrf-1",
    tenantId: "tenant-1",
    userId: "user-1",
    workflowDefinitionId: "definition-1",
    workflowInstanceId: "instance-1",
  });
  assert.equal(tenant.password, "Load-Test-123!");
  assert.equal(client.calls.length, 7);
  assert.equal(client.calls[5].init.headers["X-Tenant-Id"], "tenant-1");
  assert.equal(client.calls[6].init.headers["Idempotency-Key"], "seed-load-unit-0");
  assert.equal(client.calls[6].init.body.idempotencyKey, "seed-load-unit-0");
});

test("seedTenant falls back to cookies and the login accessToken body", async () => {
  const cookieToken = sequenceClient([
    response(201, {}),
    response(200, {}, { cookies: ["awap_access_token=cookie-token; Path=/"] }),
    response(200, {}, { cookies: ["XSRF-TOKEN=cookie-csrf; Path=/"] }),
    response(200, { id: "user-1", tenantId: "tenant-1" }),
    response(201, { id: "definition-1" }),
    response(201, {}),
    response(201, { id: "instance-1" }),
  ]);
  const tenant = await seedTenant(cookieToken, FIXTURE);
  assert.equal(tenant.token, "cookie-token");
  assert.equal(tenant.csrfToken, "cookie-csrf");

  const bodyToken = sequenceClient([
    response(201, {}),
    response(200, { accessToken: "body-token" }),
    response(200, { token: "csrf-1" }),
    ...HAPPY_RESPONSES().slice(3),
  ]);
  assert.equal((await seedTenant(bodyToken, FIXTURE)).token, "body-token");
});

test("seedTenant fails loudly on every broken step", async () => {
  const cases = [
    {
      name: "registration",
      responses: [response(400, { detail: "slug taken" })],
      pattern: /tenant registration for load-unit-0 returned 400/,
    },
    {
      name: "login status",
      responses: [response(201, {}), response(401, {})],
      pattern: /admin login for load-unit-0 returned 401/,
    },
    {
      name: "missing token",
      responses: [response(201, {}), response(200, {})],
      pattern: /issued no access token/,
    },
    {
      name: "csrf",
      responses: [response(201, {}), response(200, { accessToken: "t" }), response(500, {})],
      pattern: /CSRF bootstrap for load-unit-0 returned no token \(status 500\)/,
    },
    {
      name: "profile status",
      responses: [
        response(201, {}),
        response(200, { accessToken: "t" }),
        response(200, { token: "c" }),
        response(503, {}),
      ],
      pattern: /profile lookup for load-unit-0 returned 503/,
    },
    {
      name: "missing tenant id",
      responses: [
        response(201, {}),
        response(200, { accessToken: "t" }),
        response(200, { token: "c" }),
        response(200, { id: "user-1" }),
      ],
      pattern: /returned no tenantId\/userId/,
    },
    {
      name: "definition status",
      responses: [...HAPPY_RESPONSES().slice(0, 4), response(400, {})],
      pattern: /workflow definition create for load-unit-0 returned 400/,
    },
    {
      name: "missing definition id",
      responses: [...HAPPY_RESPONSES().slice(0, 4), response(201, {})],
      pattern: /workflow definition create for load-unit-0 returned no id/,
    },
    {
      name: "publish",
      responses: [...HAPPY_RESPONSES().slice(0, 5), response(409, {})],
      pattern: /workflow definition publish for load-unit-0 returned 409/,
    },
    {
      name: "start status",
      responses: [...HAPPY_RESPONSES().slice(0, 6), response(400, {})],
      pattern: /workflow instance start for load-unit-0 returned 400/,
    },
    {
      name: "missing instance id",
      responses: [...HAPPY_RESPONSES().slice(0, 6), response(201, {})],
      pattern: /workflow instance start for load-unit-0 returned no id/,
    },
  ];

  for (const testCase of cases) {
    await assert.rejects(
      () => seedTenant(sequenceClient(testCase.responses), FIXTURE),
      (error) => {
        assert.ok(error instanceof SeedError, `${testCase.name} must throw SeedError`);
        assert.match(error.message, testCase.pattern);
        return true;
      },
    );
  }
});

test("runWithConcurrency stops scheduling after the failure budget", async () => {
  const processed = [];
  const { results, failures } = await runWithConcurrency({
    items: [1, 2, 3, 4],
    concurrency: 1,
    maxFailures: 1,
    worker: async (item) => {
      processed.push(item);
      if (item === 2) {
        throw new Error("boom");
      }
      return item;
    },
  });
  assert.deepEqual(results, [1]);
  assert.equal(failures.length, 1);
  assert.deepEqual(processed, [1, 2]);
});

test("runWithConcurrency clamps the worker pool to the queue", async () => {
  const { results } = await runWithConcurrency({
    items: ["a"],
    concurrency: 8,
    worker: async (item) => item.toUpperCase(),
  });
  assert.deepEqual(results, ["A"]);
});

test("seedTenants sorts by index and reports failures plus unprocessed work", async () => {
  const fixtures = [0, 1, 2].map((index) => buildTenantFixture({ suffix: "s", index }));
  const client = fakeBackend({ failSlugs: new Set(["load-s-1"]) });
  const logged = [];
  const summary = await seedTenants({
    client,
    fixtures,
    concurrency: 1,
    maxFailures: 1,
    log: (line) => logged.push(line),
  });
  assert.deepEqual(
    summary.seeded.map((tenant) => tenant.index),
    [0],
  );
  assert.equal(summary.failures.length, 1);
  assert.equal(summary.failures[0].slug, "load-s-1");
  assert.match(summary.failures[0].message, /tenant registration for load-s-1 returned 400/);
  assert.equal(summary.unprocessed, 1);
  assert.deepEqual(logged, ["seeded load-s-0"]);
});

test("the seeder CLI prints usage for --help", async () => {
  const lines = [];
  assert.equal(await main(["--help"], { log: (line) => lines.push(line) }), 0);
  assert.match(lines.join("\n"), /--tenants <n>/);
});

test("the seeder CLI returns 2 on usage errors", async () => {
  const lines = [];
  assert.equal(await main(["--tenant", "5"], { log: (line) => lines.push(line) }), 2);
  assert.match(lines.join("\n"), /unknown argument "--tenant"/);
});

test("the seeder CLI dry run validates the plan without touching the API", async () => {
  const lines = [];
  const exitCode = await main(["--tenants", "1000", "--concurrency", "25", "--dry-run"], {
    log: (line) => lines.push(line),
    createClient: () => {
      throw new Error("dry run must not create a client");
    },
  });
  assert.equal(exitCode, 0);
  const plan = JSON.parse(lines.join("\n"));
  assert.equal(plan.mode, "dry-run");
  assert.equal(plan.tenants, 1000);
  assert.equal(plan.batches, 40);
  assert.equal(plan.firstSlug.startsWith("load-"), true);
  assert.equal(plan.lastSlug.endsWith("-999"), true);
});

test("the seeder CLI writes the credential file k6 reads", async () => {
  const lines = [];
  const writes = [];
  const client = fakeBackend();
  const exitCode = await main(
    ["--tenants", "2", "--suffix", "unit-run", "--output", "/tmp/tenants.json"],
    {
      log: (line) => lines.push(line),
      createClient: () => client,
      writeFile: async (path, contents) => writes.push({ path, contents }),
    },
  );
  assert.equal(exitCode, 0);
  assert.equal(writes.length, 1);
  assert.equal(writes[0].path, "/tmp/tenants.json");
  const payload = JSON.parse(writes[0].contents);
  assert.equal(payload.count, 2);
  assert.equal(payload.suffix, "unit-run");
  assert.deepEqual(
    payload.tenants.map((tenant) => tenant.slug),
    ["load-unit-run-0", "load-unit-run-1"],
  );
  assert.match(lines.join("\n"), /Seeded 2\/2 tenant\(s\)/);
});

test("the seeder CLI exits 1 and writes nothing when every tenant fails", async () => {
  const lines = [];
  const writes = [];
  const exitCode = await main(["--tenants", "2", "--concurrency", "1", "--suffix", "x"], {
    log: (line) => lines.push(line),
    createClient: () => fakeBackend({ failSlugs: new Set(["load-x-0", "load-x-1"]) }),
    writeFile: async (path, contents) => writes.push({ path, contents }),
  });
  // Both fixtures fail: the run must be red and must not publish an unusable credential file.
  assert.equal(exitCode, 1);
  assert.equal(writes.length, 0);
  assert.match(lines.join("\n"), /2 failure\(s\)/);
});

test("the seeder CLI exits 1 on a partial failure but keeps the seeded credentials", async () => {
  const writes = [];
  const exitCode = await main(["--tenants", "2", "--concurrency", "1", "--suffix", "partial"], {
    log: () => {},
    createClient: () => fakeBackend({ failSlugs: new Set(["load-partial-0"]) }),
    writeFile: async (path, contents) => writes.push({ path, contents }),
  });
  assert.equal(exitCode, 1);
  assert.equal(writes.length, 1);
  assert.equal(JSON.parse(writes[0].contents).tenants[0].slug, "load-partial-1");
});

test("the seeder CLI surfaces the summary output path and unchanged exit 0 on retries=0", async () => {
  const lines = [];
  const exitCode = await main(["--tenants", "1", "--retries", "0", "--dry-run"], {
    log: (line) => lines.push(line),
  });
  assert.equal(exitCode, 0);
  assert.match(lines.join("\n"), /"output": "load-test\/tenants.json"/);
});

test("the report CLI prints help and usage errors", async () => {
  const help = [];
  assert.equal(await reportMain(["--help"], { log: (line) => help.push(line) }), 0);
  assert.match(help.join("\n"), /--summary <path>/);

  const usage = [];
  assert.equal(await reportMain(["--summary"], { log: (line) => usage.push(line) }), 2);
  assert.match(usage.join("\n"), /expects a value/);

  const missing = [];
  assert.equal(await reportMain([], { log: (line) => missing.push(line) }), 2);
  assert.match(missing.join("\n"), /--summary is required/);

  const badMode = [];
  assert.equal(
    await reportMain(["--summary", "x", "--mode", "soak"], { log: (line) => badMode.push(line) }),
    2,
  );
  assert.match(badMode.join("\n"), /--mode must be/);

  const badTenants = [];
  assert.equal(
    await reportMain(["--dry-run", "--tenants", "0"], { log: (line) => badTenants.push(line) }),
    2,
  );
  assert.match(badTenants.join("\n"), /--tenants expects a positive integer/);
});

test("the report CLI dry run passes and can write the JSON report", async () => {
  const lines = [];
  const writes = [];
  const appended = [];
  const exitCode = await reportMain(
    ["--dry-run", "--output", "/tmp/report.json", "--step-summary", "/tmp/step.md"],
    {
      log: (line) => lines.push(line),
      writeFile: async (path, contents) => writes.push({ path, contents }),
      appendFile: async (path, contents) => appended.push({ path, contents }),
    },
  );
  assert.equal(exitCode, 0);
  assert.equal(JSON.parse(writes[0].contents).result, "pass");
  assert.equal(JSON.parse(writes[0].contents).mode, "dry-run");
  assert.match(appended[0].contents, /1000\+ tenant load test \(dry-run\)/);
  assert.match(lines.join("\n"), /SLO result: pass/);
});

test("the report CLI fails the job when a budget is exceeded", async () => {
  const summary = {
    metrics: {
      "http_req_duration{scenario:tenant_isolation}": { values: { "p(95)": 9000, "p(99)": 9000 } },
      "http_req_failed{scenario:tenant_isolation}": { values: { rate: 0.5 } },
      "http_req_duration{scenario:approval_race}": { values: { "p(95)": 1, "p(99)": 1 } },
      "http_req_failed{scenario:approval_race}": { values: { rate: 0 } },
      "http_req_duration{scenario:idempotent_start}": { values: { "p(95)": 1, "p(99)": 1 } },
      "http_req_failed{scenario:idempotent_start}": { values: { rate: 0 } },
      http_reqs: { values: { rate: 100 } },
    },
  };
  const report = { written: null };
  const exitCode = await reportMain(
    [
      "--summary",
      "/tmp/summary.json",
      "--tenants",
      "1000",
      "--mode",
      "smoke",
      "--output",
      "/tmp/report.json",
    ],
    {
      log: () => {},
      readFile: async () => JSON.stringify(summary),
      writeFile: async (path, contents) => {
        report.written = JSON.parse(contents);
      },
    },
  );
  assert.equal(exitCode, 1);
  assert.equal(report.written.result, "fail");
  assert.equal(report.written.mode, "smoke");
  assert.ok(report.written.regressions.some((entry) => entry.id === "tenant_isolation_p95"));

  const tolerated = await reportMain(
    ["--summary", "/tmp/summary.json", "--no-fail", "--output", "/tmp/report.json"],
    {
      log: () => {},
      readFile: async () => JSON.stringify(summary),
      writeFile: async () => {},
    },
  );
  assert.equal(tolerated, 0);
});

test("the report CLI reports an unreadable summary without a stack trace", async () => {
  const lines = [];
  assert.equal(
    await reportMain(["--summary", "/tmp/missing.json"], {
      log: (line) => lines.push(line),
      readFile: async () => {
        throw new Error("ENOENT");
      },
    }),
    2,
  );
  assert.match(lines.join("\n"), /cannot read the k6 summary/);

  const malformed = [];
  assert.equal(
    await reportMain(["--summary", "/tmp/bad.json"], {
      log: (line) => malformed.push(line),
      readFile: async () => "not json",
    }),
    2,
  );
  assert.match(malformed.join("\n"), /cannot read the k6 summary/);
});

test("refreshTenant re-authenticates an existing tenant without re-registering", async () => {
  const client = sequenceClient([
    response(200, { accessToken: "fresh-token" }),
    response(200, { token: "fresh-csrf" }),
  ]);
  const stored = {
    index: 3,
    slug: "load-old-3",
    email: "admin+old-3@loadtest.awap.dev",
    password: "Load-Test-123!",
    token: "stale-token",
    csrfToken: "stale-csrf",
    tenantId: "tenant-3",
    userId: "user-3",
    workflowDefinitionId: "definition-3",
    workflowInstanceId: "instance-3",
  };
  const refreshed = await refreshTenant(client, stored);
  assert.equal(refreshed.token, "fresh-token");
  assert.equal(refreshed.csrfToken, "fresh-csrf");
  assert.equal(refreshed.tenantId, "tenant-3");
  assert.equal(refreshed.workflowInstanceId, "instance-3");
  assert.deepEqual(
    client.calls.map((call) => call.path),
    ["/api/v1/auth/login", "/api/v1/auth/csrf"],
  );
});

test("refreshTenant fails when the login or the CSRF bootstrap breaks", async () => {
  const stored = {
    index: 0,
    slug: "load-old-0",
    email: "admin+old-0@loadtest.awap.dev",
    password: "Load-Test-123!",
  };
  await assert.rejects(
    () => refreshTenant(sequenceClient([response(401, {})]), stored),
    (error) =>
      error instanceof SeedError && /admin login for load-old-0 returned 401/.test(error.message),
  );
  await assert.rejects(
    () =>
      refreshTenant(
        sequenceClient([response(200, { accessToken: "t" }), response(200, {})]),
        stored,
      ),
    (error) => error instanceof SeedError && /CSRF bootstrap for load-old-0/.test(error.message),
  );
});

test("refreshTenants reports failures and keeps refreshed tenants sorted", async () => {
  const tenants = [0, 1]
    .map((index) => buildTenantFixture({ suffix: "old", index }))
    .map((fixture) => ({ ...fixture, tenantId: `t-${fixture.index}` }));
  const failing = {
    request: async (method, path) =>
      path === "/api/v1/auth/login" ? response(401, {}) : response(200, {}),
  };
  const summary = await refreshTenants({
    client: failing,
    tenants,
    concurrency: 1,
    maxFailures: 1,
    log: () => {},
  });
  assert.equal(summary.tenants.length, 0);
  assert.equal(summary.failures.length, 1);
  assert.equal(summary.unprocessed, 1);
});

test("the seeder CLI refreshes stored credentials and rewrites the file", async () => {
  const writes = [];
  const lines = [];
  const stored = {
    suffix: "old",
    tenants: [
      {
        index: 0,
        slug: "load-old-0",
        email: "admin+old-0@loadtest.awap.dev",
        password: "Load-Test-123!",
        token: "stale",
        csrfToken: "stale",
        tenantId: "tenant-0",
      },
    ],
  };
  const exitCode = await main(["--refresh", "--output", "/tmp/tenants.json"], {
    log: (line) => lines.push(line),
    readFile: async () => JSON.stringify(stored),
    writeFile: async (path, contents) => writes.push({ path, contents }),
    createClient: () => fakeBackend(),
  });
  assert.equal(exitCode, 0);
  const payload = JSON.parse(writes[0].contents);
  assert.equal(payload.tenants[0].token, "cookie-token");
  assert.equal(payload.tenants[0].csrfToken, "csrf-1");
  assert.ok(payload.refreshedAt);
  assert.match(lines.join("\n"), /Refreshed 1\/1 credential\(s\)/);
});

test("the seeder CLI refuses an unreadable or empty credential file", async () => {
  const unreadable = [];
  assert.equal(
    await main(["--refresh"], {
      log: (line) => unreadable.push(line),
      readFile: async () => {
        throw new Error("ENOENT");
      },
    }),
    2,
  );
  assert.match(unreadable.join("\n"), /cannot read the credential file/);

  const empty = [];
  assert.equal(
    await main(["--refresh"], {
      log: (line) => empty.push(line),
      readFile: async () => JSON.stringify({ tenants: [] }),
    }),
    2,
  );
  assert.match(empty.join("\n"), /contains no tenants to refresh/);
});
