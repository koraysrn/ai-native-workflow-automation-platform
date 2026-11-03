import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

const CONNECTOR = new URL("../outbox-connector.json", import.meta.url);
const COMPOSE = new URL("../../docker-compose.cdc.yml", import.meta.url);
const K8S = new URL("../../k8s/debezium-connect.yaml", import.meta.url);
const SCRIPT = new URL("../register-connector.sh", import.meta.url);

async function text(url) {
  return readFile(url, "utf8");
}

async function connectorConfig() {
  return JSON.parse(await text(CONNECTOR)).config;
}

test("connector streams every outbox partition with a dedicated slot", async () => {
  const config = await connectorConfig();
  assert.equal(config["connector.class"], "io.debezium.connector.postgresql.PostgresConnector");
  // The outbox is partitioned; the parent filter alone does not match the partition relation.
  assert.equal(config["table.include.list"], "public.outbox_events,public.outbox_events.*");
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
});

test("compose deployment uses the Debezium image env contract and mounts the connector", async () => {
  const compose = await text(COMPOSE);
  assert.match(compose, /quay\.io\/debezium\/connect/);
  assert.match(compose, /CONFIG_STORAGE_TOPIC: awap\.connect\.configs/);
  assert.match(compose, /OFFSET_STORAGE_TOPIC: awap\.connect\.offsets/);
  assert.match(compose, /STATUS_STORAGE_TOPIC: awap\.connect\.status/);
  assert.match(compose, /GROUP_ID: awap-connect/);
  assert.match(compose, /BOOTSTRAP_SERVERS: kafka:29092/);
  assert.match(compose, /CONNECT_PLUGIN_PATH: \/kafka\/connect/);
  assert.match(compose, /CONNECT_PRODUCER_OVERRIDE_ENABLE_IDEMPOTENCE: "true"/);
  assert.match(compose, /outbox-connector\.json:\/kafka\/connectors\/outbox-connector\.json/);
  assert.match(compose, /healthcheck:/);
  assert.match(compose, /external: true/);
});

test("kubernetes manifests use the Debezium env contract and secret refs", async () => {
  const manifest = await text(K8S);
  for (const kind of ["kind: Deployment", "kind: Service", "kind: Secret", "kind: ConfigMap", "kind: Job"]) {
    assert.ok(manifest.includes(kind), `missing ${kind}`);
  }
  for (const name of [
    "GROUP_ID",
    "CONFIG_STORAGE_TOPIC",
    "OFFSET_STORAGE_TOPIC",
    "STATUS_STORAGE_TOPIC",
    "CONNECT_PLUGIN_PATH",
    "CONNECT_PRODUCER_OVERRIDE_ENABLE_IDEMPOTENCE",
  ]) {
    assert.ok(manifest.includes(`name: ${name}`), `missing env ${name}`);
  }
  assert.match(manifest, /secretKeyRef:\n\s+name: awap-connect-db/);
  assert.match(manifest, /readinessProbe:/);
  assert.match(manifest, /prometheus\.io\/scrape: "true"/);
});

test("kubernetes ConfigMap carries exactly the canonical connector JSON", async () => {
  const manifest = await text(K8S);
  const canonical = JSON.parse(await text(CONNECTOR));
  const block = manifest.match(
    /outbox-connector\.json: \|\n([\s\S]*?)\n---\napiVersion: batch\/v1/,
  );
  assert.ok(block, "the connector ConfigMap is missing");
  const embedded = JSON.parse(block[1].replace(/^    /gm, ""));
  assert.deepEqual(embedded, canonical);
});

test("kubernetes registration Job is idempotent and reads credentials from the Secret", async () => {
  const manifest = await text(K8S);
  assert.match(manifest, /kind: Job/);
  assert.match(manifest, /name: awap-connect-register/);
  assert.match(manifest, /image: alpine:3\.20/);
  assert.match(
    manifest,
    /envsubst '\$\{DB_HOST\} \$\{DB_PORT\} \$\{DB_USER\} \$\{DB_PASSWORD\} \$\{DB_NAME\}'/,
  );
  assert.match(manifest, /jq -c '\.config'/);
  assert.match(manifest, /-X PUT/);
  assert.match(manifest, /-X POST/);
  assert.match(manifest, /connectors\/\$\{CONNECTOR_NAME\}\/status/);
  assert.match(manifest, /\.connector\.state == "RUNNING"/);
  assert.match(manifest, /all\(\.tasks\[\]; \.state == "RUNNING"\)/);
  assert.match(manifest, /name: awap-connect-connector/);
  assert.match(manifest, /secretKeyRef:\n\s+name: awap-connect-db/);
  assert.match(manifest, /key: DB_PASSWORD/);
  assert.doesNotMatch(manifest, /DB_PASSWORD: awap/);
});

test("registration script is idempotent and reads secrets from the environment", async () => {
  const script = await text(SCRIPT);
  assert.match(script, /set -euo pipefail/);
  assert.match(script, /envsubst/);
  // Only the DB_* placeholders are substituted, so Debezium's ${routedByValue} survives.
  assert.match(script, /envsubst '\$\{DB_HOST\} \$\{DB_PORT\} \$\{DB_USER\} \$\{DB_PASSWORD\} \$\{DB_NAME\}'/);
  assert.match(script, /Missing required environment variable/);
  assert.match(script, /-X PUT/);
  assert.match(script, /config_payload/);
  assert.match(script, /\/status/);
  assert.doesNotMatch(script, /DB_PASSWORD=awap/);
});
