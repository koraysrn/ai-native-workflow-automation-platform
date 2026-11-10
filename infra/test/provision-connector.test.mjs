import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import {
  applyDbEnvironment,
  connectorConfig,
  provisionConnector,
  waitForStatus,
} from "../scripts/provision-connector.mjs";

const CONNECTOR_FILE = fileURLToPath(
  new URL("../debezium/outbox-connector.json", import.meta.url),
);

function silentLogger() {
  return { log: () => {}, error: () => {} };
}

function response({ ok = true, status = 200, body = {} } = {}) {
  return { ok, status, json: async () => body };
}

test("applyDbEnvironment substitutes only DB_* placeholders", () => {
  const rendered = applyDbEnvironment(
    '{"host":"${DB_HOST}","db":"${DB_NAME}","route":"${routedByValue}"}',
    { DB_HOST: "db.internal", DB_NAME: "awap" },
  );
  assert.equal(rendered, '{"host":"db.internal","db":"awap","route":"${routedByValue}"}');
});

test("applyDbEnvironment rejects an explicitly empty required variable", () => {
  assert.throws(
    () => applyDbEnvironment("${DB_HOST}", { DB_HOST: "" }),
    /Missing required environment variable DB_HOST/,
  );
});

test("connectorConfig unwraps the create envelope", () => {
  assert.deepEqual(connectorConfig({ name: "x", config: { a: 1 } }), { a: 1 });
  assert.deepEqual(connectorConfig({ a: 1 }), { a: 1 });
});

test("waitForStatus succeeds on a later attempt", async () => {
  let attempt = 0;
  const status = await waitForStatus({
    connectUrl: "http://connect:8083",
    fetchImpl: async () => {
      attempt += 1;
      return attempt < 3 ? response({ ok: false, status: 404 }) : response({ body: { state: "RUNNING" } });
    },
    sleep: async () => {},
  });
  assert.equal(attempt, 3);
  assert.deepEqual(status, { state: "RUNNING" });
});

test("waitForStatus fails after the bounded retry budget", async () => {
  await assert.rejects(
    () =>
      waitForStatus({
        connectUrl: "http://connect:8083",
        fetchImpl: async () => {
          throw new Error("ECONNREFUSED");
        },
        sleep: async () => {},
        attempts: 2,
      }),
    /did not become ready after 2 attempts/,
  );
});

test("provisionConnector creates the connector when absent and waits for status", async () => {
  const traceDir = await mkdtemp(join(tmpdir(), "awap-connector-"));
  const traceFile = join(traceDir, "trace.txt");
  const calls = [];
  const outcome = await provisionConnector({
    connectUrl: "http://connect:8083",
    connectorFile: CONNECTOR_FILE,
    env: { DB_HOST: "postgres", DB_NAME: "awap" },
    traceFile,
    logger: silentLogger(),
    fetchImpl: async (url, init = {}) => {
      calls.push(`${init.method ?? "GET"} ${url}`);
      if (url.endsWith("/connectors/awap-outbox-connector") && !init.method) {
        return response({ ok: false, status: 404 });
      }
      if (url.endsWith("/connectors") && init.method === "POST") {
        return response({ status: 201 });
      }
      return response({ body: { connector: { state: "RUNNING" } } });
    },
  });
  assert.equal(outcome, "created");
  assert.deepEqual(calls, [
    "GET http://connect:8083/connectors/awap-outbox-connector",
    "POST http://connect:8083/connectors",
    "GET http://connect:8083/connectors/awap-outbox-connector/status",
  ]);
  assert.match(await readFile(traceFile, "utf8"), /connector:created/);
});

test("provisionConnector updates an existing connector with the config object", async () => {
  const traceDir = await mkdtemp(join(tmpdir(), "awap-connector-"));
  const traceFile = join(traceDir, "trace.txt");
  let putBody;
  const outcome = await provisionConnector({
    connectUrl: "http://connect:8083",
    connectorFile: CONNECTOR_FILE,
    env: { DB_HOST: "postgres", DB_NAME: "awap" },
    traceFile,
    logger: silentLogger(),
    fetchImpl: async (url, init = {}) => {
      if (url.endsWith("/connectors/awap-outbox-connector") && !init.method) {
        return response({ ok: true });
      }
      if (url.endsWith("/config") && init.method === "PUT") {
        putBody = JSON.parse(init.body);
        return response({ status: 200 });
      }
      return response({ body: { connector: { state: "RUNNING" } } });
    },
  });
  assert.equal(outcome, "updated");
  assert.equal(putBody["connector.class"], "io.debezium.connector.postgresql.PostgresConnector");
  assert.match(await readFile(traceFile, "utf8"), /connector:updated/);
});

test("provisionConnector fails when the worker rejects the create", async () => {
  await assert.rejects(
    () =>
      provisionConnector({
        connectUrl: "http://connect:8083",
        connectorFile: CONNECTOR_FILE,
        env: { DB_HOST: "postgres", DB_NAME: "awap" },
        traceFile: null,
        logger: silentLogger(),
        fetchImpl: async () => response({ ok: false, status: 500 }),
      }),
    /Creating connector failed with status 500/,
  );
});

test("provisionConnector fails when the worker rejects the update", async () => {
  await assert.rejects(
    () =>
      provisionConnector({
        connectUrl: "http://connect:8083",
        connectorFile: CONNECTOR_FILE,
        env: { DB_HOST: "postgres", DB_NAME: "awap" },
        traceFile: null,
        logger: silentLogger(),
        fetchImpl: async (url, init = {}) =>
          url.endsWith("/config") ? response({ ok: false, status: 409 }) : response({ ok: true }),
      }),
    /Updating connector failed with status 409/,
  );
});

test("provisionConnector dry-run renders the template and touches no endpoint", async () => {
  const traceDir = await mkdtemp(join(tmpdir(), "awap-connector-"));
  const traceFile = join(traceDir, "trace.txt");
  let called = false;
  const outcome = await provisionConnector({
    connectUrl: "http://connect:8083",
    connectorFile: CONNECTOR_FILE,
    env: { DB_HOST: "postgres", DB_NAME: "awap" },
    traceFile,
    logger: silentLogger(),
    dryRun: true,
    fetchImpl: async () => {
      called = true;
      return response({});
    },
  });
  assert.equal(outcome, "dry-run");
  assert.equal(called, false);
  assert.match(await readFile(traceFile, "utf8"), /connector:dry-run/);
});
