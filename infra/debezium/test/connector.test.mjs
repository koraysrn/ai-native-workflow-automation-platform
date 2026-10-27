import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

const CONNECTOR = new URL("../outbox-connector.json", import.meta.url);
const WORKER = new URL("../connect.properties", import.meta.url);
const COMPOSE = new URL("../../docker-compose.cdc.yml", import.meta.url);
const K8S = new URL("../../k8s/debezium-connect.yaml", import.meta.url);
const SCRIPT = new URL("../register-connector.sh", import.meta.url);

async function text(url) {
  return readFile(url, "utf8");
}

async function connectorConfig() {
  return JSON.parse(await text(CONNECTOR)).config;
}

test("connector streams only the outbox table with a dedicated slot", async () => {
  const config = await connectorConfig();
  assert.equal(config["connector.class"], "io.debezium.connector.postgresql.PostgresConnector");
  assert.equal(config["table.include.list"], "public.outbox_events");
  assert.equal(config["slot.name"], "awap_outbox");
  assert.equal(config["publication.name"], "awap_outbox_pub");
  assert.equal(config["publication.autocreate.mode"], "filtered");
  assert.equal(config["snapshot.mode"], "no_data");
  assert.equal(config["tombstones.on.delete"], "false");
});

test("EventRouter routes by aggregate type with the outbox columns", async () => {
  const config = await connectorConfig();
  assert.match(config["transforms"], /\boutbox\b/);
  assert.equal(config["transforms.outbox.type"], "io.debezium.transforms.outbox.EventRouter");
  assert.equal(config["transforms.outbox.table.field.event.id"], "id");
  assert.equal(config["transforms.outbox.table.field.event.key"], "aggregate_id");
  assert.equal(config["transforms.outbox.table.field.event.payload"], "payload_json");
  assert.equal(config["transforms.outbox.route.by.field"], "aggregate_type");
  assert.equal(config["transforms.outbox.route.topic.replacement"], "outbox.event.${routedByValue}");
  assert.match(config["transforms.outbox.table.fields.additional.placement"], /tenant_id:header:tenantId/);
});

test("dead letters are routed instead of blocking the connector", async () => {
  const config = await connectorConfig();
  assert.equal(config["errors.tolerance"], "all");
  assert.equal(config["errors.deadletterqueue.topic.name"], "awap.cdc.dead-letter");
  assert.equal(config["errors.deadletterqueue.context.headers.enable"], "true");
  assert.equal(config["value.converter.schemas.enable"], "false");
});

test("credentials come from placeholders, never literals", async () => {
  const raw = await text(CONNECTOR);
  assert.match(raw, /"\$\{DB_PASSWORD\}"/);
  assert.doesNotMatch(raw, /postgres:\/\/[^"]*:[^"]*@/);
  const worker = await text(WORKER);
  assert.match(worker, /bootstrap\.servers=\$\{CONNECT_BOOTSTRAP_SERVERS\}/);
  assert.match(worker, /enable\.idempotence=true/);
});

test("compose deployment mounts the worker config and the connector", async () => {
  const compose = await text(COMPOSE);
  assert.match(compose, /quay\.io\/debezium\/connect/);
  assert.match(compose, /connect\.properties:\/kafka\/config\/connect\.properties/);
  assert.match(compose, /outbox-connector\.json:\/kafka\/connectors\/outbox-connector\.json/);
  assert.match(compose, /healthcheck:/);
  assert.match(compose, /external: true/);
});

test("kubernetes manifests define config, deployment and service with secret refs", async () => {
  const manifest = await text(K8S);
  for (const kind of ["kind: ConfigMap", "kind: Deployment", "kind: Service", "kind: Secret"]) {
    assert.ok(manifest.includes(kind), `missing ${kind}`);
  }
  assert.match(manifest, /secretKeyRef:\n\s+name: awap-connect-db/);
  assert.match(manifest, /readinessProbe:/);
  assert.match(manifest, /prometheus\.io\/scrape: "true"/);
});

test("registration script is idempotent and reads secrets from the environment", async () => {
  const script = await text(SCRIPT);
  assert.match(script, /set -euo pipefail/);
  assert.match(script, /envsubst/);
  assert.match(script, /Missing required environment variable/);
  assert.match(script, /-X PUT/);
  assert.match(script, /\/status/);
  assert.doesNotMatch(script, /DB_PASSWORD=awap/);
});
