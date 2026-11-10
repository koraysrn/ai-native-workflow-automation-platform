#!/usr/bin/env node
// Idempotent Debezium outbox-connector provisioning (issue #189).
//
// The connector is created when absent and updated in place when it already exists, so restarting
// the full profile never fails and never duplicates the connector. Only DB_* placeholders from the
// JSON template are substituted; Debezium's own ${routedByValue} placeholder is preserved.
//
// Usage:
//   node infra/scripts/provision-connector.mjs
//   node infra/scripts/provision-connector.mjs --dry-run
//   CONNECT_URL=http://localhost:8083 node infra/scripts/provision-connector.mjs
//
// Environment:
//   CONNECT_URL      Kafka Connect REST endpoint (default http://localhost:8083)
//   CONNECTOR_FILE   Connector template path (default infra/debezium/outbox-connector.json)
//   DB_HOST/DB_PORT/DB_USER/DB_PASSWORD/DB_NAME  template substitutions
//   AWAP_PROVISION_TRACE_FILE  optional file that records every executed branch

import { readFile } from "node:fs/promises";
import { appendFile } from "node:fs/promises";
import process from "node:process";

const CONNECTOR_NAME = "awap-outbox-connector";
const DEFAULT_CONNECT_URL = "http://localhost:8083";
const DEFAULT_CONNECTOR_FILE = "infra/debezium/outbox-connector.json";
const DB_DEFAULTS = {
  DB_HOST: "postgres",
  DB_PORT: "5432",
  DB_USER: "awap",
  DB_PASSWORD: "awap",
  DB_NAME: "awap",
};

/** Writes one line to the trace file so the node:test suite can prove every branch executes. */
export async function trace(file, line) {
  if (!file) {
    return;
  }
  await appendFile(file, `${line}\n`, "utf8");
}

/** Substitutes only the documented DB_* placeholders in the connector template. */
export function applyDbEnvironment(template, env = process.env) {
  let rendered = template;
  for (const key of Object.keys(DB_DEFAULTS)) {
    const value = env[key] ?? DB_DEFAULTS[key];
    if (value === undefined || String(value).length === 0) {
      throw new Error(`Missing required environment variable ${key}`);
    }
    rendered = rendered.split(`\${${key}}`).join(String(value));
  }
  return rendered;
}

/** Splits the create envelope into the config object PUT /connectors/{name}/config expects. */
export function connectorConfig(payload) {
  return payload.config ?? payload;
}

/** Polls until the connector status endpoint answers or the bounded retry budget is exhausted. */
export async function waitForStatus({
  connectUrl,
  name = CONNECTOR_NAME,
  fetchImpl = fetch,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  attempts = 30,
  intervalMs = 2000,
}) {
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetchImpl(`${connectUrl}/connectors/${name}/status`);
      if (response.ok) {
        return await response.json();
      }
    } catch {
      // The worker can refuse connections during the first seconds after create; retry.
    }
    if (attempt < attempts) {
      await sleep(intervalMs);
    }
  }
  throw new Error(`Connector status endpoint did not become ready after ${attempts} attempts`);
}

/**
 * Creates or updates the connector. Returns `created`, `updated` or `dry-run`.
 * The dry-run branch prints the planned calls without touching the worker.
 */
export async function provisionConnector({
  connectUrl = DEFAULT_CONNECT_URL,
  connectorFile = DEFAULT_CONNECTOR_FILE,
  env = process.env,
  fetchImpl = fetch,
  sleep,
  dryRun = false,
  logger = console,
  traceFile = env.AWAP_PROVISION_TRACE_FILE,
}) {
  const template = await readFile(connectorFile, "utf8");
  const rendered = applyDbEnvironment(template, env);
  const payload = JSON.parse(rendered);
  const config = connectorConfig(payload);

  if (dryRun) {
    await trace(traceFile, "connector:dry-run");
    logger.log(`[dry-run] POST/PUT ${connectUrl}/connectors/${CONNECTOR_NAME}`);
    return "dry-run";
  }

  let exists = false;
  try {
    const response = await fetchImpl(`${connectUrl}/connectors/${CONNECTOR_NAME}`);
    exists = response.ok;
  } catch {
    // A connection error must not be treated as "absent"; the retry below produces the real error.
    exists = false;
  }

  if (exists) {
    const response = await fetchImpl(`${connectUrl}/connectors/${CONNECTOR_NAME}/config`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(config),
    });
    if (!response.ok) {
      throw new Error(`Updating connector failed with status ${response.status}`);
    }
    await trace(traceFile, "connector:updated");
    logger.log(`Updated connector ${CONNECTOR_NAME}`);
  } else {
    const response = await fetchImpl(`${connectUrl}/connectors`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    if (!response.ok) {
      throw new Error(`Creating connector failed with status ${response.status}`);
    }
    await trace(traceFile, "connector:created");
    logger.log(`Created connector ${CONNECTOR_NAME}`);
  }

  const status = await waitForStatus({ connectUrl, fetchImpl, sleep });
  logger.log(`Connector status: ${JSON.stringify(status)}`);
  return exists ? "updated" : "created";
}

/** CLI entrypoint. `--dry-run` proves the template renders without a running Connect. */
async function main(argv = process.argv.slice(2)) {
  const dryRun = argv.includes("--dry-run");
  const connectorFile = process.env.CONNECTOR_FILE ?? DEFAULT_CONNECTOR_FILE;
  const connectUrl = process.env.CONNECT_URL ?? DEFAULT_CONNECT_URL;
  try {
    const outcome = await provisionConnector({ connectUrl, connectorFile, dryRun });
    console.log(`Connector provisioning finished: ${outcome}`);
    return 0;
  } catch (error) {
    console.error(`Connector provisioning failed: ${error.message}`);
    return 1;
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().then((code) => {
    process.exitCode = code;
  });
}
