import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { parse } from "yaml";

const COMPOSE = new URL("../docker-compose.yml", import.meta.url);
const ENV_EXAMPLE = new URL("../.env.example", import.meta.url);

async function compose() {
  return parse(await readFile(COMPOSE, "utf8"));
}

function profilesOf(service) {
  return service.profiles ?? [];
}

function inProfile(service, profile) {
  return profilesOf(service).includes(profile);
}

// Long-running services must be health-gated; one-shot provisioners exit on their own and are
// validated by their trace/exit code instead.
const ONE_SHOT = new Set(["kafka-init", "minio-init", "debezium-init"]);

test("the full profile contains every runtime dependency and application (issue #189)", async () => {
  const model = await compose();
  for (const name of ["minio", "clamav", "ai-layer"]) {
    assert.ok(inProfile(model.services[name], "full"), `${name} must be enabled by the full profile`);
  }
  for (const name of ["connect", "kafka-init", "minio-init", "debezium-init"]) {
    assert.ok(inProfile(model.services[name], "full"), `${name} must be part of the full profile`);
  }
  // The always-on core is what `docker compose --profile full up` starts in addition to those.
  for (const name of [
    "postgres",
    "redis",
    "kafka",
    "schema-registry",
    "opensearch",
    "vault",
    "prometheus",
    "grafana",
    "otel-collector",
    "backend",
    "frontend",
  ]) {
    assert.ok(model.services[name], `${name} must exist in Compose`);
  }
});

test("every long-running service has a healthcheck", async () => {
  const model = await compose();
  const missing = Object.entries(model.services)
    .filter(([name]) => !ONE_SHOT.has(name))
    .filter(([, service]) => !service.healthcheck)
    .map(([name]) => name);
  assert.deepEqual(missing, [], `services without a healthcheck: ${missing.join(", ")}`);
});

test("service dependencies use readiness conditions, not bare service names", async () => {
  const model = await compose();
  for (const name of ["schema-registry", "kafka-init", "minio-init", "debezium-init", "frontend"]) {
    const dependency = model.services[name].depends_on;
    assert.equal(typeof dependency, "object", `${name} must use long-form depends_on`);
    for (const value of Object.values(dependency)) {
      assert.equal(value.condition, "service_healthy", `${name} must wait for readiness`);
    }
  }
  const backendDeps = model.services.backend.depends_on;
  assert.equal(backendDeps.kafka.condition, "service_healthy");
  for (const name of ["kafka", "minio", "clamav", "ai-layer", "otel-collector"]) {
    assert.equal(
      backendDeps[name].required,
      false,
      `${name} must stay optional for the core stack`,
    );
  }
  // A slow optional service (ClamAV signatures, the AI image build) must not hold the backend
  // down; it only needs to have started. Its own healthcheck is asserted by `make up`.
  for (const name of ["minio", "clamav", "ai-layer", "otel-collector"]) {
    assert.equal(backendDeps[name].condition, "service_started");
  }
});

test("the full profile turns on Kafka, MinIO, ClamAV, the AI layer and OpenSearch", async () => {
  const model = await compose();
  const env = model.services.backend.environment;
  assert.equal(env.KAFKA_ENABLED, "${KAFKA_ENABLED:-true}");
  assert.equal(env.CLAMAV_ENABLED, "${CLAMAV_ENABLED:-true}");
  assert.equal(env.AI_NODE_ENABLED, "${AI_NODE_ENABLED:-true}");
  // The AI layer service is full-profile only; `make up` and the E2E job set this explicitly.
  assert.equal(env.AI_LAYER_ENABLED, "${AI_LAYER_ENABLED:-false}");
  assert.equal(env.OPENSEARCH_ENABLED, "${OPENSEARCH_ENABLED:-true}");
  assert.equal(env.FILE_MANAGE_BUCKETS, "${FILE_MANAGE_BUCKETS:-true}");
  assert.equal(env.AI_LAYER_URL, "${AI_LAYER_URL:-http://ai-layer:8000}");
  assert.equal(env.MINIO_ENDPOINT, "${MINIO_ENDPOINT:-http://minio:9000}");
  assert.equal(env.CLAMAV_HOST, "clamav");
  // The E2E job turns this on so it can assert the aiLayer health component.
  assert.equal(env.HEALTH_SHOW_DETAILS, "${HEALTH_SHOW_DETAILS:-never}");
});

test("the bucket and topic provisioners are idempotent", async () => {
  const model = await compose();
  const minioCommand = model.services["minio-init"].command.join("\n");
  assert.match(minioCommand, /mb --ignore-existing/);
  const kafkaCommand = model.services["kafka-init"].command.join("\n");
  assert.match(kafkaCommand, /--create --if-not-exists/);
  assert.match(kafkaCommand, /workflow\.task\.events/);
  assert.match(kafkaCommand, /analytics\.events/);
});

test("the seed and connector provisioners are wired into the profile", async () => {
  const model = await compose();
  assert.match(model.services["debezium-init"].command.join(" "), /provision-connector\.mjs/);
  assert.equal(model.services["debezium-init"].environment.CONNECT_URL, "http://connect:8083");
});

test("no real secret is committed: .env.example only documents local placeholders", async () => {
  const envExample = await readFile(ENV_EXAMPLE, "utf8");
  assert.match(envExample, /change-me/);
  assert.ok(!/ghp_[A-Za-z0-9]{20,}/.test(envExample));
  assert.ok(!/AKIA[0-9A-Z]{16}/.test(envExample));
});
