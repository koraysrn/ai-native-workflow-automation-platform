#!/usr/bin/env node
// Live observability proof (issue #190).
//
// Proves, against a real running stack, that a real HTTP request produces:
//   * a Prometheus series for awap_task_decisions_total,
//   * an UP backend scrape target,
//   * a non-empty Grafana dashboard query result,
//   * an OTLP span accepted by the collector,
//   * a JSON log line carrying the same trace ID as the span.
//
// Every assertion has an explicit failure message naming the missing metric/target/trace so a
// broken stack fails with the cause instead of being skipped. The node:test suite drives every
// branch with fakes; CI runs the live proof and uploads the JSON evidence artifact.
//
// Usage:
//   node infra/observability/prove-observability.mjs
//   node infra/observability/prove-observability.mjs --dry-run

import { execFile } from "node:child_process";
import { randomBytes } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

export const METRIC = "awap_task_decisions_total";
export const BACKEND_TARGET = "backend:8080";
export const DASHBOARD_UID = "awap-overview";
export const DASHBOARD_EXPR = "sum by (decision) (increase(awap_task_decisions_total[5m]))";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export function hex(bytes) {
  return randomBytes(bytes).toString("hex");
}

/** Joins Set-Cookie values into a Cookie request header, preserving the last value per name. */
export function cookiesToHeader(setCookies, previous = "") {
  const jar = new Map();
  for (const pair of previous.split(";")) {
    const [name, ...rest] = pair.trim().split("=");
    if (name) {
      jar.set(name, rest.join("="));
    }
  }
  for (const cookie of setCookies) {
    const [pair] = cookie.split(";");
    const separator = pair.indexOf("=");
    if (separator > 0) {
      jar.set(pair.slice(0, separator).trim(), pair.slice(separator + 1).trim());
    }
  }
  return [...jar.entries()].map(([name, value]) => `${name}=${value}`).join("; ");
}

async function json(response) {
  try {
    return await response.json();
  } catch {
    return {};
  }
}

/** Registers a unique tenant, logs in, bootstraps CSRF and resolves the current user. */
export async function createSession({ baseUrl, fetchImpl = fetch, suffix = `${Date.now()}` }) {
  const slug = `observability-${suffix}`;
  const email = `observability+${suffix}@awap.dev`;
  const password = "Observability-Proof-1!";

  const registration = await fetchImpl(`${baseUrl}/api/v1/tenants`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      slug,
      name: "Observability Proof",
      adminEmail: email,
      adminPassword: password,
      adminFullName: "Observability Proof",
    }),
  });
  if (registration.status !== 201) {
    throw new Error(`Tenant registration failed with status ${registration.status}`);
  }

  const login = await fetchImpl(`${baseUrl}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ tenantSlug: slug, email, password }),
  });
  if (!login.ok) {
    throw new Error(`Login failed with status ${login.status}`);
  }
  let cookie = cookiesToHeader(login.headers?.getSetCookie?.() ?? []);

  const csrf = await fetchImpl(`${baseUrl}/api/v1/auth/csrf`, { headers: { Cookie: cookie } });
  if (!csrf.ok) {
    throw new Error(`CSRF bootstrap failed with status ${csrf.status}`);
  }
  const csrfBody = await json(csrf);
  const csrfToken = csrfBody.token ?? "";
  cookie = cookiesToHeader(csrf.headers?.getSetCookie?.() ?? [], cookie);
  if (!csrfToken) {
    throw new Error("CSRF bootstrap returned no token");
  }

  const headers = { Cookie: cookie, "X-XSRF-TOKEN": csrfToken, "Content-Type": "application/json" };
  const me = await fetchImpl(`${baseUrl}/api/v1/auth/me`, { headers });
  if (!me.ok) {
    throw new Error(`Current-user lookup failed with status ${me.status}`);
  }
  const user = await json(me);

  return { slug, email, headers, userId: user.id, tenantId: user.tenantId };
}

/** Creates a task assigned to the caller and approves it through the public REST API. */
export async function triggerTaskDecision({ baseUrl, session, traceId, fetchImpl = fetch }) {
  const created = await fetchImpl(`${baseUrl}/api/v1/tasks`, {
    method: "POST",
    headers: session.headers,
    body: JSON.stringify({
      title: `Observability proof ${traceId.slice(0, 8)}`,
      description: "Created by the observability proof",
      workflowInstanceId: null,
      nodeExecutionId: null,
      assigneeId: session.userId,
    }),
  });
  if (created.status !== 201) {
    throw new Error(`Task creation failed with status ${created.status}`);
  }
  const task = await json(created);

  const approved = await fetchImpl(`${baseUrl}/api/v1/tasks/${task.id}/approve`, {
    method: "POST",
    headers: {
      ...session.headers,
      // Spring Boot propagates W3C traceparent into the trace, the JSON log MDC and the collector.
      traceparent: `00-${traceId}-${hex(8)}-01`,
    },
    body: JSON.stringify({ comment: "observability proof" }),
  });
  if (!approved.ok) {
    throw new Error(`Task approval failed with status ${approved.status}`);
  }
  return task.id;
}

/** Asserts the metric series exists and its value is greater than zero. */
export function assertMetricSeries(result, metric = METRIC) {
  const series = result?.data?.result ?? [];
  const matching = series.filter((entry) => entry.metric?.__name__ === metric);
  if (matching.length === 0) {
    throw new Error(`Prometheus series ${metric} is missing`);
  }
  const positive = matching.some((entry) => Number.parseFloat(entry.value?.[1] ?? "0") > 0);
  if (!positive) {
    throw new Error(`Prometheus series ${metric} exists but no sample is greater than zero`);
  }
  return matching;
}

/** Asserts the backend scrape target is UP. */
export function assertTargetUp(result, target = BACKEND_TARGET) {
  const targets = result?.data?.activeTargets ?? [];
  const match = targets.find(
    (entry) => entry.labels?.job === "awap-backend" || entry.scrapeUrl?.includes(target),
  );
  if (!match) {
    throw new Error(`Prometheus target ${target} is missing`);
  }
  if (match.health !== "up") {
    throw new Error(`Prometheus target ${target} is ${match.health ?? "unknown"}`);
  }
  return match;
}

/** Asserts a Grafana datasource query returned at least one non-empty frame. */
export function assertGrafanaNonEmpty(result) {
  const frames = result?.results?.A?.frames ?? [];
  const nonEmpty = frames.some((frame) =>
    (frame.data?.values ?? []).some((column) => Array.isArray(column) && column.length > 0),
  );
  if (!nonEmpty) {
    throw new Error("Grafana dashboard query returned an empty result");
  }
  return frames;
}

/** Asserts a trace ID appears in a log stream with a clear service name. */
export function assertTraceInLogs(logs, traceId, service) {
  if (!logs.includes(traceId)) {
    throw new Error(`Trace ${traceId} is missing from the ${service} logs`);
  }
  // Compose prefixes every line with "<service>-1 | ", so the JSON object is not at column 0.
  const line = logs
    .split("\n")
    .find((entry) => entry.includes(traceId) && entry.includes('"mdc"'));
  if (!line) {
    throw new Error(`Trace ${traceId} is present in ${service} logs but not in a JSON log line`);
  }
  return line;
}

/** Builds the OTLP/HTTP JSON payload for one proof span. */
export function otlpPayload(traceId, spanId = hex(8)) {
  const now = Date.now() * 1_000_000;
  return {
    resourceSpans: [
      {
        resource: {
          attributes: [
            { key: "service.name", value: { stringValue: "awap-observability-proof" } },
          ],
        },
        scopeSpans: [
          {
            scope: { name: "awap.observability.proof" },
            spans: [
              {
                traceId,
                spanId,
                name: "awap.observability.proof",
                kind: 1,
                startTimeUnixNano: String(now),
                endTimeUnixNano: String(now + 1_000_000),
                attributes: [],
              },
            ],
          },
        ],
      },
    ],
  };
}

/** Posts a synthetic span straight to the collector. */
export async function sendOtlpSpan({ collectorUrl, traceId, fetchImpl = fetch }) {
  const response = await fetchImpl(`${collectorUrl}/v1/traces`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(otlpPayload(traceId)),
  });
  if (!response.ok) {
    throw new Error(`Sending the OTLP span failed with status ${response.status}`);
  }
  return response.status;
}

/** Polls a service's Compose logs for a needle, then asserts the JSON correlation. */
export async function waitForLogEvidence({
  exec,
  composeFile,
  service,
  needle,
  attempts = 30,
  intervalMs = 5000,
  expectJson = false,
  sleepImpl = sleep,
}) {
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    const { stdout } = await exec(
      "docker",
      ["compose", "-f", composeFile, "--profile", "full", "logs", "--no-color", "--since", "15m", service],
      {},
    );
    if (stdout.includes(needle)) {
      return expectJson ? assertTraceInLogs(stdout, needle, service) : stdout;
    }
    if (attempt < attempts) {
      await sleepImpl(intervalMs);
    }
  }
  throw new Error(`Trace ${needle} is missing from the ${service} logs`);
}

async function requestJson(fetchImpl, url, init) {
  const response = await fetchImpl(url, init);
  if (!response.ok) {
    throw new Error(`GET ${url} failed with status ${response.status}`);
  }
  return json(response);
}

/** Polls Prometheus until the metric series is non-empty. */
export async function waitForPrometheus({
  prometheusUrl,
  fetchImpl = fetch,
  sleepImpl = sleep,
  attempts = 30,
  intervalMs = 5000,
  query = METRIC,
}) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const result = await requestJson(
        fetchImpl,
        `${prometheusUrl}/api/v1/query?query=${encodeURIComponent(query)}`,
        {},
      );
      return assertMetricSeries(result);
    } catch (error) {
      lastError = error;
      if (attempt < attempts) {
        await sleepImpl(intervalMs);
      }
    }
  }
  throw lastError;
}

/** Polls Prometheus until the backend target reports UP. */
export async function waitForTarget({
  prometheusUrl,
  fetchImpl = fetch,
  sleepImpl = sleep,
  attempts = 30,
  intervalMs = 5000,
}) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const result = await requestJson(fetchImpl, `${prometheusUrl}/api/v1/targets?state=active`, {});
      return assertTargetUp(result);
    } catch (error) {
      lastError = error;
      if (attempt < attempts) {
        await sleepImpl(intervalMs);
      }
    }
  }
  throw lastError;
}

/** Polls the Grafana dashboard datasource query until it returns a non-empty frame. */
export async function waitForGrafana({
  grafanaUrl,
  fetchImpl = fetch,
  sleepImpl = sleep,
  attempts = 30,
  intervalMs = 5000,
  username = process.env.GF_ADMIN_USER ?? "admin",
  password = process.env.GF_ADMIN_PASSWORD ?? "admin",
}) {
  const authorization = `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}`;
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const dashboard = await requestJson(fetchImpl, `${grafanaUrl}/api/dashboards/uid/${DASHBOARD_UID}`, {
        headers: { Authorization: authorization },
      });
      if (!dashboard.dashboard) {
        throw new Error(`Grafana dashboard ${DASHBOARD_UID} is missing`);
      }
      const query = {
        queries: [
          {
            refId: "A",
            datasource: { uid: "prometheus", type: "prometheus" },
            expr: DASHBOARD_EXPR,
            instant: true,
          },
        ],
        from: "now-5m",
        to: "now",
      };
      const response = await fetchImpl(`${grafanaUrl}/api/ds/query`, {
        method: "POST",
        headers: { Authorization: authorization, "Content-Type": "application/json" },
        body: JSON.stringify(query),
      });
      if (!response.ok) {
        throw new Error(`Grafana datasource query failed with status ${response.status}`);
      }
      return assertGrafanaNonEmpty(await json(response));
    } catch (error) {
      lastError = error;
      if (attempt < attempts) {
        await sleepImpl(intervalMs);
      }
    }
  }
  throw lastError;
}

/** Runs the complete proof and writes machine-readable evidence. */
export async function runProof({
  baseUrl = "http://localhost:8080",
  prometheusUrl = "http://localhost:9090",
  grafanaUrl = "http://localhost:3001",
  collectorUrl = "http://localhost:4318",
  composeFile = "infra/docker-compose.yml",
  evidenceDir = "observability-evidence",
  fetchImpl = fetch,
  exec,
  traceFile,
  logger = console,
  dryRun = false,
  suffix,
  attempts = 30,
  intervalMs = 5000,
  sleepImpl = sleep,
}) {
  const trace = async (line) => {
    if (traceFile) {
      const { appendFile } = await import("node:fs/promises");
      await appendFile(traceFile, `${line}\n`, "utf8");
    }
  };
  await trace("proof:start");
  if (dryRun) {
    await trace("proof:dry-run");
    logger.log("[dry-run] register tenant, approve a task, query Prometheus/Grafana, send OTLP span");
    await trace("proof:complete");
    return { dryRun: true };
  }

  const traceId = hex(16);
  const session = await createSession({ baseUrl, fetchImpl, suffix: suffix ?? `${Date.now()}` });
  await trace("proof:session");
  const taskId = await triggerTaskDecision({ baseUrl, session, traceId, fetchImpl });
  await trace("proof:decision");

  const metricSeries = await waitForPrometheus({
    prometheusUrl,
    fetchImpl,
    attempts,
    intervalMs,
    sleepImpl,
  });
  await trace("proof:prometheus-metric");

  const target = await waitForTarget({ prometheusUrl, fetchImpl, attempts, intervalMs, sleepImpl });
  await trace("proof:prometheus-target");

  const grafanaFrames = await waitForGrafana({
    grafanaUrl,
    fetchImpl,
    attempts,
    intervalMs,
    sleepImpl,
  });
  await trace("proof:grafana");

  await sendOtlpSpan({ collectorUrl, traceId, fetchImpl });
  const collectorLog = await waitForLogEvidence({
    exec,
    composeFile,
    service: "otel-collector",
    needle: traceId,
    attempts,
    intervalMs,
    sleepImpl,
  });
  await trace("proof:otlp-span");

  const backendLogLine = await waitForLogEvidence({
    exec,
    composeFile,
    service: "backend",
    needle: traceId,
    attempts,
    intervalMs,
    expectJson: true,
    sleepImpl,
  });
  await trace("proof:log-correlation");

  const evidence = {
    generatedAt: new Date().toISOString(),
    traceId,
    taskId,
    tenantId: session.tenantId,
    metric: {
      name: METRIC,
      series: metricSeries.map((entry) => ({ metric: entry.metric, value: entry.value?.[1] })),
    },
    prometheusTarget: { target: BACKEND_TARGET, health: target.health },
    grafana: { dashboardUid: DASHBOARD_UID, frames: grafanaFrames.length },
    collector: { spanTraceId: traceId, evidenceBytes: collectorLog.length },
    log: { traceId, jsonLine: backendLogLine },
  };
  await mkdir(evidenceDir, { recursive: true });
  await writeFile(
    join(evidenceDir, "observability-evidence.json"),
    `${JSON.stringify(evidence, null, 2)}\n`,
    "utf8",
  );
  await trace("proof:complete");
  logger.log(`Observability proof passed for trace ${traceId}; evidence written to ${evidenceDir}`);
  return evidence;
}

export function parseArgs(argv) {
  return {
    dryRun: argv.includes("--dry-run"),
    evidenceDir: "observability-evidence",
    composeFile: "infra/docker-compose.yml",
  };
}

async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  const exec = async (command, args, execOptions = {}) => {
    const result = await execFileAsync(command, args, { ...execOptions, maxBuffer: 20 * 1024 * 1024 });
    return { stdout: result.stdout, stderr: result.stderr };
  };
  try {
    await runProof({
      dryRun: options.dryRun,
      evidenceDir: options.evidenceDir,
      composeFile: options.composeFile,
      exec,
      traceFile: process.env.AWAP_OBSERVABILITY_TRACE_FILE,
    });
    return 0;
  } catch (error) {
    console.error(`Observability proof failed: ${error.message}`);
    return 1;
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  main().then((code) => {
    process.exitCode = code;
  });
}
