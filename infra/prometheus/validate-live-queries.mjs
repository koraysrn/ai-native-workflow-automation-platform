#!/usr/bin/env node
/**
 * Executes every provisioned Grafana panel query against a real Prometheus (issue #160).
 *
 * What it proves:
 *   1. Prometheus scrapes a backend exposition and a CDC (`postgres_exporter`) exposition.
 *   2. Every panel target returns at least one series with representative synthetic data.
 *   3. Every tenant/channel-filtered target returns an explicitly expected empty result for a
 *      sentinel value that matches no series (the "no data" case from issue #130).
 *
 * Usage:
 *   node infra/prometheus/validate-live-queries.mjs
 *   PROMETHEUS_LIVE_URL=http://localhost:9090 node infra/prometheus/validate-live-queries.mjs
 *
 * Without `PROMETHEUS_LIVE_URL` the script starts its own synthetic exporter and a throwaway
 * Prometheus container (Docker required, Linux/CI friendly).
 */
import { spawn, spawnSync } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { renderBackendMetrics, renderExporterMetrics } from "./synthetic-metrics.mjs";

const DASHBOARD = new URL("../grafana/provisioning/dashboards/awap.json", import.meta.url);
const EXPECTATIONS = new URL("../grafana/query-expectations.json", import.meta.url);
const IMAGE = process.env.PROMETHEUS_IMAGE ?? "prom/prometheus:v2.55.0";
const EXTERNAL_URL = process.env.PROMETHEUS_LIVE_URL;
const VARIABLE = /\$(tenant|channel)/;
const ALL_VALUE = ".*";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function freePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

async function query(baseUrl, expr) {
  const url = new URL("/api/v1/query", baseUrl);
  url.searchParams.set("query", expr);
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Prometheus answered HTTP ${response.status} for: ${expr}`);
  }
  const body = await response.json();
  if (body.status !== "success") {
    throw new Error(`Prometheus rejected "${expr}": ${body.error}`);
  }
  return body.data.result;
}

async function waitFor(check, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const value = await check();
      if (value) {
        return value;
      }
    } catch (error) {
      lastError = error;
    }
    await sleep(500);
  }
  throw new Error(`Timed out waiting for ${label}${lastError ? `: ${lastError.message}` : ""}`);
}

function substitute(expr, value) {
  return String(expr).replaceAll("$tenant", value).replaceAll("$channel", value);
}

async function startSyntheticExporter() {
  let tick = 0;
  const server = createServer((request, response) => {
    const path = new URL(request.url, "http://localhost").pathname;
    tick += 1;
    if (path === "/metrics") {
      response.writeHead(200, { "content-type": "text/plain; version=0.0.4" });
      response.end(renderBackendMetrics(tick));
      return;
    }
    if (path === "/postgres-metrics") {
      response.writeHead(200, { "content-type": "text/plain; version=0.0.4" });
      response.end(renderExporterMetrics(tick));
      return;
    }
    response.writeHead(404);
    response.end();
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "0.0.0.0", resolve);
  });
  return { server, port: server.address().port };
}

function writePrometheusConfig(metricsPort) {
  return [
    "global:",
    "  scrape_interval: 1s",
    "  evaluation_interval: 1s",
    "scrape_configs:",
    "  - job_name: awap-backend",
    "    metrics_path: /metrics",
    "    static_configs:",
    `      - targets: ["host.docker.internal:${metricsPort}"]`,
    "  - job_name: awap-postgres-exporter",
    "    metrics_path: /postgres-metrics",
    "    static_configs:",
    `      - targets: ["host.docker.internal:${metricsPort}"]`,
    "",
  ].join("\n");
}

async function startPrometheus(configDir) {
  const port = await freePort();
  const name = `awap-live-prom-${process.pid}-${Date.now()}`;
  const child = spawn(
    "docker",
    [
      "run",
      "--rm",
      "--name",
      name,
      "--add-host",
      "host.docker.internal:host-gateway",
      "-p",
      `127.0.0.1:${port}:9090`,
      "-v",
      `${configDir}:/etc/prometheus:ro`,
      IMAGE,
      "--config.file=/etc/prometheus/prometheus.yml",
      "--storage.tsdb.path=/tmp/prometheus-data",
    ],
    { stdio: ["ignore", "pipe", "pipe"] },
  );
  let stderr = "";
  child.stderr.on("data", (chunk) => {
    stderr += chunk.toString();
  });
  const spawnFailure = new Promise((_, reject) => child.once("error", reject));
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    await Promise.race([
      waitFor(
        async () => {
          const response = await fetch(`${baseUrl}/-/ready`);
          return response.ok;
        },
        180_000,
        `Prometheus at ${baseUrl}`,
      ),
      spawnFailure,
    ]);
  } catch (error) {
    child.kill("SIGTERM");
    throw new Error(`${error.message}\nPrometheus output:\n${stderr.slice(-2000)}`);
  }
  return {
    baseUrl,
    async stop() {
      child.kill("SIGTERM");
      await Promise.race([new Promise((resolve) => child.once("exit", resolve)), sleep(10_000)]);
      spawnSync("docker", ["rm", "-f", name], { stdio: "ignore" });
    },
  };
}

function record(checks, name, ok, detail) {
  checks.push({ name, ok, detail });
  const status = ok ? "PASS" : "FAIL";
  console.log(`${status} ${name}${detail ? ` — ${detail}` : ""}`);
}

async function main() {
  const dashboard = JSON.parse(await readFile(DASHBOARD, "utf8"));
  const expectations = JSON.parse(await readFile(EXPECTATIONS, "utf8"));
  const sentinel = expectations.sentinel?.value ?? "__awap_no_data__";

  if (EXTERNAL_URL) {
    console.log(`Using the Prometheus at ${EXTERNAL_URL} (PROMETHEUS_LIVE_URL).`);
  }

  const synthetic = EXTERNAL_URL ? undefined : await startSyntheticExporter();
  let configDir;
  let prometheus;
  const checks = [];

  try {
    if (synthetic) {
      configDir = await mkdtemp(join(tmpdir(), "awap-prometheus-"));
      const configFile = join(configDir, "prometheus.yml");
      await writeFile(configFile, writePrometheusConfig(synthetic.port));
      // mkdtemp creates a 0700 directory; the container's `nobody` user must read it.
      await chmod(configDir, 0o755);
      await chmod(configFile, 0o644);
      prometheus = await startPrometheus(configDir);
    }
    const baseUrl = EXTERNAL_URL ?? prometheus.baseUrl;

    if (synthetic) {
      try {
        await waitFor(
          async () => {
            // `count_over_time` returns the number of samples per series; `min` keeps the
            // check strict across both scrape targets instead of counting series.
            const result = await query(
              baseUrl,
              'min(count_over_time(up{job=~"awap-backend|awap-postgres-exporter"}[5m]))',
            );
            return result.length === 1 && Number(result[0].value[1]) >= 3;
          },
          60_000,
          "at least three scrapes from the synthetic exporter",
        );
      } catch (error) {
        const targets = await fetch(`${baseUrl}/api/v1/targets`)
          .then((response) => response.json())
          .catch(() => undefined);
        if (targets) {
          console.error(JSON.stringify(targets.data?.activeTargets, null, 2));
        }
        throw error;
      }
    }

    // Acceptance: local Prometheus scrapes the backend and the CDC exporter.
    for (const job of ["awap-backend", "awap-postgres-exporter"]) {
      const result = await query(baseUrl, `up{job="${job}"}`);
      const healthy = result.length === 1 && Number(result[0].value[1]) === 1;
      record(checks, `scrape target ${job}`, healthy, healthy ? "up == 1" : "target is not up");
    }
    for (const expr of [
      'pg_replication_slots_active{slot_name="awap_outbox"}',
      'pg_replication_slots_pg_wal_lsn_diff{slot_name="awap_outbox"}',
    ]) {
      const result = await query(baseUrl, expr);
      record(checks, `CDC exporter series ${expr}`, result.length > 0, `${result.length} series`);
    }

    const declared = new Set(Object.keys(expectations.panels ?? {}));
    for (const panel of dashboard.panels) {
      if (!declared.has(panel.title)) {
        record(checks, `panel "${panel.title}" is declared`, false, "missing in query-expectations.json");
      }
    }

    for (const panel of dashboard.panels) {
      const expected = expectations.panels?.[panel.title];
      if (!expected) {
        continue;
      }
      for (const target of panel.targets) {
        const label = `panel "${panel.title}" [${target.refId}]`;
        const expr = substitute(target.expr, ALL_VALUE);
        const result = await query(baseUrl, expr);
        const ok = expected === "empty" ? result.length === 0 : result.length > 0;
        record(
          checks,
          `${label} (${expected})`,
          ok,
          `${result.length} series${ok ? "" : ` for: ${expr}`}`,
        );

        if (VARIABLE.test(target.expr)) {
          const sentinelResult = await query(baseUrl, substitute(target.expr, sentinel));
          const empty = expectations.sentinel?.expect === "empty";
          const sentinelOk = empty ? sentinelResult.length === 0 : sentinelResult.length > 0;
          record(
            checks,
            `${label} sentinel ${sentinel} (expected ${empty ? "empty" : "series"})`,
            sentinelOk,
            `${sentinelResult.length} series`,
          );
        }
      }
    }
  } finally {
    if (prometheus) {
      await prometheus.stop();
    }
    if (synthetic) {
      await new Promise((resolve) => synthetic.server.close(resolve));
    }
    if (configDir) {
      await rm(configDir, { recursive: true, force: true });
    }
  }

  const failed = checks.filter((check) => !check.ok);
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed.`);
  if (failed.length > 0) {
    console.error(`\n${failed.length} check(s) failed.`);
    process.exitCode = 1;
  }
}

main().catch((error) => {
  console.error(error.stack ?? error.message);
  process.exitCode = 1;
});
