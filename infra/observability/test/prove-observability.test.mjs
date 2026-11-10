import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import {
  METRIC,
  assertGrafanaNonEmpty,
  assertMetricSeries,
  assertTargetUp,
  assertTraceInLogs,
  cookiesToHeader,
  createSession,
  otlpPayload,
  runProof,
  sendOtlpSpan,
  triggerTaskDecision,
  waitForGrafana,
  waitForLogEvidence,
  waitForPrometheus,
  waitForTarget,
} from "../prove-observability.mjs";

function response({ ok = true, status = 200, body = {}, setCookies = [] } = {}) {
  return {
    ok,
    status,
    json: async () => body,
    headers: { getSetCookie: () => setCookies },
  };
}

function silentLogger() {
  return { log: () => {}, error: () => {} };
}

const session = {
  headers: { Cookie: "awap_access_token=abc", "X-XSRF-TOKEN": "csrf" },
  userId: "user-1",
  tenantId: "tenant-1",
};

test("cookiesToHeader merges login and CSRF cookies, last value wins", () => {
  const header = cookiesToHeader(
    ["XSRF-TOKEN=csrf-2; Path=/", "awap_session=1; Path=/"],
    "awap_access_token=abc; XSRF-TOKEN=csrf-1",
  );
  assert.equal(header, "awap_access_token=abc; XSRF-TOKEN=csrf-2; awap_session=1");
});

test("createSession registers, logs in, bootstraps CSRF and resolves the user", async () => {
  const calls = [];
  const result = await createSession({
    baseUrl: "http://api",
    suffix: "42",
    fetchImpl: async (url, init = {}) => {
      calls.push({ url, method: init.method ?? "GET", body: init.body });
      if (url.endsWith("/api/v1/tenants")) {
        return response({ status: 201 });
      }
      if (url.endsWith("/api/v1/auth/login")) {
        return response({ setCookies: ["awap_access_token=token; Path=/", "awap_session=1; Path=/"] });
      }
      if (url.endsWith("/api/v1/auth/csrf")) {
        return response({ body: { token: "csrf" }, setCookies: ["XSRF-TOKEN=csrf; Path=/"] });
      }
      return response({ body: { id: "user-1", tenantId: "tenant-1" } });
    },
  });
  assert.equal(result.userId, "user-1");
  assert.equal(result.tenantId, "tenant-1");
  assert.match(result.headers.Cookie, /awap_access_token=token/);
  assert.equal(result.headers["X-XSRF-TOKEN"], "csrf");
  assert.equal(JSON.parse(calls[0].body).slug, "observability-42");
});

test("createSession fails with the registration status", async () => {
  await assert.rejects(
    () => createSession({ baseUrl: "http://api", fetchImpl: async () => response({ status: 409 }) }),
    /Tenant registration failed with status 409/,
  );
});

test("createSession fails with the login status", async () => {
  await assert.rejects(
    () =>
      createSession({
        baseUrl: "http://api",
        fetchImpl: async (url) =>
          url.endsWith("/tenants") ? response({ status: 201 }) : response({ ok: false, status: 401 }),
      }),
    /Login failed with status 401/,
  );
});

test("createSession fails when CSRF bootstrap returns no token", async () => {
  await assert.rejects(
    () =>
      createSession({
        baseUrl: "http://api",
        fetchImpl: async (url) => {
          if (url.endsWith("/tenants")) return response({ status: 201 });
          if (url.endsWith("/login")) return response({});
          return response({ body: {} });
        },
      }),
    /CSRF bootstrap returned no token/,
  );
});

test("createSession fails when the current-user lookup is rejected", async () => {
  await assert.rejects(
    () =>
      createSession({
        baseUrl: "http://api",
        fetchImpl: async (url) => {
          if (url.endsWith("/tenants")) return response({ status: 201 });
          if (url.endsWith("/login")) return response({});
          if (url.endsWith("/csrf")) return response({ body: { token: "csrf" } });
          return response({ ok: false, status: 500 });
        },
      }),
    /Current-user lookup failed with status 500/,
  );
});

test("triggerTaskDecision creates and approves a task with a W3C traceparent", async () => {
  const calls = [];
  const taskId = await triggerTaskDecision({
    baseUrl: "http://api",
    session,
    traceId: "a".repeat(32),
    fetchImpl: async (url, init = {}) => {
      calls.push({ url, headers: init.headers });
      return url.endsWith("/tasks") ? response({ status: 201, body: { id: "task-1" } }) : response({});
    },
  });
  assert.equal(taskId, "task-1");
  assert.match(calls[1].url, /\/tasks\/task-1\/approve$/);
  assert.match(calls[1].headers.traceparent, /^00-a{32}-[0-9a-f]{16}-01$/);
});

test("triggerTaskDecision fails when task creation or approval fails", async () => {
  await assert.rejects(
    () =>
      triggerTaskDecision({
        baseUrl: "http://api",
        session,
        traceId: "b".repeat(32),
        fetchImpl: async () => response({ status: 400 }),
      }),
    /Task creation failed with status 400/,
  );
  await assert.rejects(
    () =>
      triggerTaskDecision({
        baseUrl: "http://api",
        session,
        traceId: "b".repeat(32),
        fetchImpl: async (url) =>
          url.endsWith("/tasks") ? response({ status: 201, body: { id: "t" } }) : response({ ok: false, status: 403 }),
      }),
    /Task approval failed with status 403/,
  );
});

test("assertMetricSeries names the missing metric and rejects zero samples", () => {
  assert.throws(
    () => assertMetricSeries({ data: { result: [] } }),
    new RegExp(`${METRIC} is missing`),
  );
  assert.throws(
    () =>
      assertMetricSeries({
        data: { result: [{ metric: { __name__: METRIC }, value: [0, "0"] }] },
      }),
    /no sample is greater than zero/,
  );
  const series = assertMetricSeries({
    data: { result: [{ metric: { __name__: METRIC, decision: "APPROVED" }, value: [0, "1"] }] },
  });
  assert.equal(series.length, 1);
});

test("assertTargetUp names a missing target and a down target", () => {
  assert.throws(() => assertTargetUp({ data: { activeTargets: [] } }), /target backend:8080 is missing/);
  assert.throws(
    () =>
      assertTargetUp({
        data: {
          activeTargets: [{ labels: { job: "awap-backend" }, health: "down", scrapeUrl: "backend:8080" }],
        },
      }),
    /target backend:8080 is down/,
  );
  const target = assertTargetUp({
    data: { activeTargets: [{ labels: { job: "awap-backend" }, health: "up" }] },
  });
  assert.equal(target.health, "up");
});

test("assertGrafanaNonEmpty rejects an empty dashboard result", () => {
  assert.throws(() => assertGrafanaNonEmpty({}), /empty result/);
  assert.throws(
    () => assertGrafanaNonEmpty({ results: { A: { frames: [{ data: { values: [[], []] } }] } } }),
    /empty result/,
  );
  const frames = assertGrafanaNonEmpty({
    results: { A: { frames: [{ data: { values: [[1], [2]] } }] } },
  });
  assert.equal(frames.length, 1);
});

test("assertTraceInLogs requires the trace in a JSON log line", () => {
  const traceId = "c".repeat(32);
  assert.throws(() => assertTraceInLogs("nothing here", traceId, "backend"), /missing from the backend logs/);
  assert.throws(
    () => assertTraceInLogs(`plain text ${traceId}`, traceId, "backend"),
    /not in a JSON log line/,
  );
  const line = assertTraceInLogs(`{"mdc":{"traceId":"${traceId}"}}`, traceId, "backend");
  assert.match(line, /traceId/);
});

test("waitForLogEvidence polls until the needle appears", async () => {
  let calls = 0;
  const line = await waitForLogEvidence({
    exec: async () => {
      calls += 1;
      return { stdout: calls < 2 ? "nothing" : `{"mdc":{"traceId":"abc"}}` };
    },
    composeFile: "infra/docker-compose.yml",
    service: "backend",
    needle: "abc",
    attempts: 3,
    intervalMs: 0,
    expectJson: true,
    sleepImpl: async () => {},
  });
  assert.match(line, /abc/);
  assert.equal(calls, 2);
});

test("waitForLogEvidence fails with the service name after the retry budget", async () => {
  await assert.rejects(
    () =>
      waitForLogEvidence({
        exec: async () => ({ stdout: "" }),
        composeFile: "infra/docker-compose.yml",
        service: "otel-collector",
        needle: "deadbeef",
        attempts: 2,
        intervalMs: 0,
        sleepImpl: async () => {},
      }),
    /Trace deadbeef is missing from the otel-collector logs/,
  );
});

test("otlpPayload is a valid single-span OTLP JSON envelope", () => {
  const traceId = "d".repeat(32);
  const payload = otlpPayload(traceId);
  const span = payload.resourceSpans[0].scopeSpans[0].spans[0];
  assert.equal(span.traceId, traceId);
  assert.equal(span.name, "awap.observability.proof");
});

test("sendOtlpSpan fails when the collector rejects the span", async () => {
  await assert.rejects(
    () =>
      sendOtlpSpan({
        collectorUrl: "http://collector",
        traceId: "e".repeat(32),
        fetchImpl: async () => response({ ok: false, status: 502 }),
      }),
    /Sending the OTLP span failed with status 502/,
  );
});

test("waitForPrometheus retries and then succeeds", async () => {
  let calls = 0;
  const result = await waitForPrometheus({
    prometheusUrl: "http://prom",
    attempts: 3,
    intervalMs: 0,
    sleepImpl: async () => {},
    fetchImpl: async () => {
      calls += 1;
      return response({
        body: calls < 2 ? { data: { result: [] } } : { data: { result: [{ metric: { __name__: METRIC }, value: [0, "2"] }] } },
      });
    },
  });
  assert.equal(result.length, 1);
});

test("waitForPrometheus surfaces the missing metric after the budget", async () => {
  await assert.rejects(
    () =>
      waitForPrometheus({
        prometheusUrl: "http://prom",
        attempts: 2,
        intervalMs: 0,
        sleepImpl: async () => {},
        fetchImpl: async () => response({ body: { data: { result: [] } } }),
      }),
    /Prometheus series awap_task_decisions_total is missing/,
  );
});

test("waitForTarget surfaces a down target after the budget", async () => {
  await assert.rejects(
    () =>
      waitForTarget({
        prometheusUrl: "http://prom",
        attempts: 2,
        intervalMs: 0,
        sleepImpl: async () => {},
        fetchImpl: async () =>
          response({ body: { data: { activeTargets: [{ labels: { job: "awap-backend" }, health: "down" }] } } }),
      }),
    /Prometheus target backend:8080 is down/,
  );
});

test("waitForGrafana queries the provisioned dashboard and its datasource", async () => {
  const urls = [];
  const frames = await waitForGrafana({
    grafanaUrl: "http://grafana",
    attempts: 1,
    intervalMs: 0,
    sleepImpl: async () => {},
    fetchImpl: async (url) => {
      urls.push(url);
      if (url.includes("/api/dashboards/uid/")) {
        return response({ body: { dashboard: { uid: "awap-overview" } } });
      }
      return response({ body: { results: { A: { frames: [{ data: { values: [[1]] } }] } } } });
    },
  });
  assert.equal(frames.length, 1);
  assert.match(urls[0], /awap-overview/);
  assert.match(urls[1], /\/api\/ds\/query$/);
});

test("waitForGrafana fails when the dashboard is missing", async () => {
  await assert.rejects(
    () =>
      waitForGrafana({
        grafanaUrl: "http://grafana",
        attempts: 1,
        intervalMs: 0,
        sleepImpl: async () => {},
        fetchImpl: async () => response({ body: {} }),
      }),
    /Grafana dashboard awap-overview is missing/,
  );
});

test("runProof dry-run records the branch without touching the stack", async () => {
  const directory = await mkdtemp(join(tmpdir(), "awap-proof-"));
  const traceFile = join(directory, "trace.txt");
  let called = false;
  const result = await runProof({
    dryRun: true,
    traceFile,
    logger: silentLogger(),
    fetchImpl: async () => {
      called = true;
      return response({});
    },
    exec: async () => {
      called = true;
      return { stdout: "" };
    },
  });
  assert.equal(result.dryRun, true);
  assert.equal(called, false);
  const trace = await readFile(traceFile, "utf8");
  assert.match(trace, /proof:start/);
  assert.match(trace, /proof:dry-run/);
  assert.match(trace, /proof:complete/);
});

test("runProof proves every hop and writes the evidence file", async () => {
  const directory = await mkdtemp(join(tmpdir(), "awap-proof-"));
  const traceFile = join(directory, "trace.txt");
  let capturedTraceId = "";
  const fetchImpl = async (url, init = {}) => {
    if (url.endsWith("/api/v1/tenants")) return response({ status: 201 });
    if (url.endsWith("/api/v1/auth/login")) {
      return response({ setCookies: ["awap_access_token=token; Path=/"] });
    }
    if (url.endsWith("/api/v1/auth/csrf")) return response({ body: { token: "csrf" } });
    if (url.endsWith("/api/v1/auth/me")) return response({ body: { id: "user-1", tenantId: "tenant-1" } });
    if (url.endsWith("/api/v1/tasks") && init.method === "POST") return response({ status: 201, body: { id: "task-1" } });
    if (url.endsWith("/approve")) return response({});
    if (url.includes("/api/v1/query")) {
      return response({ body: { data: { result: [{ metric: { __name__: METRIC }, value: [0, "1"] }] } } });
    }
    if (url.includes("/api/v1/targets")) {
      return response({ body: { data: { activeTargets: [{ labels: { job: "awap-backend" }, health: "up" }] } } });
    }
    if (url.includes("/api/dashboards/uid/")) return response({ body: { dashboard: { uid: "awap-overview" } } });
    if (url.endsWith("/api/ds/query")) {
      return response({ body: { results: { A: { frames: [{ data: { values: [[1]] } }] } } } });
    }
    if (url.endsWith("/v1/traces")) {
      capturedTraceId = JSON.parse(init.body).resourceSpans[0].scopeSpans[0].spans[0].traceId;
      return response({});
    }
    throw new Error(`unexpected URL ${url}`);
  };
  const exec = async (command, args) => {
    const service = args.at(-1);
    if (service === "otel-collector") {
      return { stdout: `Span #0\n    Trace ID       : ${capturedTraceId}\n` };
    }
    return { stdout: `{"mdc":{"traceId":"${capturedTraceId}"},"message":"request"}` };
  };

  const evidence = await runProof({
    evidenceDir: directory,
    traceFile,
    logger: silentLogger(),
    fetchImpl,
    exec,
    attempts: 1,
    intervalMs: 0,
    sleepImpl: async () => {},
  });
  assert.equal(evidence.traceId, capturedTraceId);
  assert.equal(evidence.taskId, "task-1");
  const written = JSON.parse(await readFile(join(directory, "observability-evidence.json"), "utf8"));
  assert.equal(written.traceId, capturedTraceId);
  assert.equal(written.prometheusTarget.health, "up");
  const trace = await readFile(traceFile, "utf8");
  for (const branch of [
    "proof:session",
    "proof:decision",
    "proof:prometheus-metric",
    "proof:prometheus-target",
    "proof:grafana",
    "proof:otlp-span",
    "proof:log-correlation",
    "proof:complete",
  ]) {
    assert.ok(trace.includes(branch), `missing ${branch}`);
  }
});

test("runProof fails when the metric never appears", async () => {
  const directory = await mkdtemp(join(tmpdir(), "awap-proof-"));
  const fetchImpl = async (url) => {
    if (url.endsWith("/tenants")) return response({ status: 201 });
    if (url.endsWith("/login")) return response({ setCookies: ["awap_access_token=t"] });
    if (url.endsWith("/csrf")) return response({ body: { token: "csrf" } });
    if (url.endsWith("/me")) return response({ body: { id: "u", tenantId: "t" } });
    if (url.endsWith("/tasks")) return response({ status: 201, body: { id: "task-1" } });
    if (url.endsWith("/approve")) return response({});
    return response({ body: { data: { result: [] } } });
  };
  await assert.rejects(
    () =>
      runProof({
        evidenceDir: directory,
        traceFile: null,
        logger: silentLogger(),
        fetchImpl,
        exec: async () => ({ stdout: "" }),
        attempts: 1,
        intervalMs: 0,
        sleepImpl: async () => {},
      }),
    /Prometheus series awap_task_decisions_total is missing/,
  );
});
