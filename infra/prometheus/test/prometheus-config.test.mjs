import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

const CONFIG = new URL("../prometheus.yml", import.meta.url);
const ALERT_RULES = new URL("../alert-rules.yml", import.meta.url);
const COMPOSE = new URL("../../docker-compose.yml", import.meta.url);

test("Prometheus scrapes the backend and the CDC exporter (issue #160)", async () => {
  const config = await readFile(CONFIG, "utf8");
  assert.match(config, /job_name: awap-backend/);
  assert.match(config, /metrics_path: \/actuator\/prometheus/);
  assert.match(config, /targets: \["backend:8080"\]/);
  assert.match(config, /job_name: awap-postgres-exporter/);
  assert.match(config, /targets: \["postgres-exporter:9187"\]/);
  assert.match(config, /alert-rules\.yml/);
  assert.match(config, /cdc-alert-rules\.yml/);
});

test("the instance-down alert targets the job Prometheus actually scrapes", async () => {
  const rules = await readFile(ALERT_RULES, "utf8");
  assert.match(rules, /up\{job="awap-backend"\} == 0/);
  assert.ok(!rules.includes('job="awap-identity"'), "stale job name left in the alert rules");
});

test("Compose mounts both rule files so a local Prometheus evaluates them", async () => {
  const compose = await readFile(COMPOSE, "utf8");
  assert.match(compose, /alert-rules\.yml:\/etc\/prometheus\/alert-rules\.yml:ro/);
  assert.match(compose, /cdc-alert-rules\.yml:\/etc\/prometheus\/cdc-alert-rules\.yml:ro/);
  assert.match(compose, /postgres-exporter:/);
  assert.match(compose, /collector\.replication_slot/);
  // Grafana must receive both the datasource and the dashboard provider/JSON.
  assert.match(compose, /\.\/grafana\/provisioning\/datasources:\/etc\/grafana\/provisioning\/datasources:ro/);
  assert.match(compose, /\.\/grafana\/provisioning\/dashboards:\/etc\/grafana\/provisioning\/dashboards:ro/);
});
