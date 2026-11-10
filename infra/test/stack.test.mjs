import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import {
  DEFAULT_PORTS,
  SEED,
  assertPortsFree,
  down,
  findPortConflicts,
  parseArgs,
  provisionStack,
  reset,
  seedStack,
  up,
  waitForEndpoint,
  waitForStack,
} from "../scripts/stack.mjs";

function silentLogger() {
  const lines = [];
  return { lines, log: (line) => lines.push(line), error: (line) => lines.push(line) };
}

function response({ ok = true, status = 200, body = {}, json } = {}) {
  return {
    ok,
    status,
    json: json ?? (async () => body),
  };
}

async function traceFile() {
  const directory = await mkdtemp(join(tmpdir(), "awap-stack-"));
  return join(directory, "trace.txt");
}

test("findPortConflicts ignores this project's own containers and reports foreign ports", () => {
  const output = [
    JSON.stringify({ Names: "infra-postgres-1", Ports: "0.0.0.0:5432->5432/tcp" }),
    JSON.stringify({ Names: "other-backend", Ports: "0.0.0.0:8080->8080/tcp" }),
    JSON.stringify({ Names: "other-db", Ports: "[::]:5432->5432/tcp" }),
    "not-json",
  ].join("\n");
  const conflicts = findPortConflicts(output, DEFAULT_PORTS, "infra");
  assert.deepEqual(
    conflicts.map((item) => item.port).sort((a, b) => a - b),
    [5432, 8080],
  );
  assert.deepEqual(conflicts.map((item) => item.container).sort(), ["other-backend", "other-db"]);
});

test("findPortConflicts accepts a short docker port rendering", () => {
  const output = JSON.stringify({ Names: "other", Ports: "9090->9090/tcp" });
  assert.deepEqual(findPortConflicts(output, [9090], "infra"), [{ port: 9090, container: "other" }]);
});

test("assertPortsFree passes when nothing conflicts", async () => {
  const file = await traceFile();
  const exec = async () => ({ stdout: "", stderr: "" });
  await assertPortsFree({ exec, traceFile: file, logger: silentLogger() });
  assert.match(await readFile(file, "utf8"), /up:port-checked/);
});

test("assertPortsFree fails with the conflicting port and container", async () => {
  const exec = async () => ({
    stdout: JSON.stringify({ Names: "other", Ports: "0.0.0.0:8080->8080/tcp" }),
    stderr: "",
  });
  await assert.rejects(
    () => assertPortsFree({ exec, traceFile: null, logger: silentLogger() }),
    /Port conflict detected before startup: 8080 \(other\)/,
  );
});

test("waitForEndpoint retries and succeeds", async () => {
  let calls = 0;
  const fetchImpl = async () => {
    calls += 1;
    return calls === 3 ? response({ ok: true }) : response({ ok: false, status: 503 });
  };
  const file = await traceFile();
  await waitForEndpoint({
    name: "backend",
    url: "http://localhost:8080/actuator/health/readiness",
    fetchImpl,
    sleep: async () => {},
    attempts: 5,
    traceFile: file,
    logger: silentLogger(),
  });
  assert.equal(calls, 3);
  assert.match(await readFile(file, "utf8"), /wait:ready:backend/);
});

test("waitForEndpoint treats a connection error as retryable, then times out clearly", async () => {
  const file = await traceFile();
  await assert.rejects(
    () =>
      waitForEndpoint({
        name: "backend",
        url: "http://localhost:8080/actuator/health/readiness",
        fetchImpl: async () => {
          throw new Error("ECONNREFUSED");
        },
        sleep: async () => {},
        attempts: 2,
        traceFile: file,
        logger: silentLogger(),
      }),
    /backend did not become ready within 2 attempts/,
  );
  assert.match(await readFile(file, "utf8"), /wait:timeout:backend/);
});

test("waitForStack waits for the full profile including MinIO and the AI layer", async () => {
  const requested = [];
  await waitForStack({
    profile: "full",
    fetchImpl: async (url) => {
      requested.push(url);
      return response({ ok: true });
    },
    sleep: async () => {},
    traceFile: null,
    logger: silentLogger(),
  });
  assert.deepEqual(requested, [
    "http://localhost:8080/actuator/health/readiness",
    "http://localhost:3000",
    "http://localhost:8081/subjects",
    "http://localhost:9090/-/ready",
    "http://localhost:3001/api/health",
    "http://localhost:9000/minio/health/live",
    "http://localhost:8000/api/v1/health",
  ]);
});

test("waitForStack waits only for the documented core in the minimal profile", async () => {
  const requested = [];
  await waitForStack({
    profile: "minimal",
    fetchImpl: async (url) => {
      requested.push(url);
      return response({ ok: true });
    },
    sleep: async () => {},
    traceFile: null,
    logger: silentLogger(),
  });
  assert.deepEqual(requested, ["http://localhost:8080/actuator/health/readiness", "http://localhost:3000"]);
});

test("provisionStack runs every one-shot provisioner exactly once", async () => {
  const commands = [];
  const file = await traceFile();
  await provisionStack({
    exec: async (command, args) => {
      commands.push([command, ...args].join(" "));
      return { stdout: "", stderr: "" };
    },
    composeFile: "infra/docker-compose.yml",
    traceFile: file,
    logger: silentLogger(),
  });
  assert.deepEqual(commands, [
    "docker compose -f infra/docker-compose.yml --profile full run --rm --no-deps kafka-init",
    "docker compose -f infra/docker-compose.yml --profile full run --rm --no-deps minio-init",
    "docker compose -f infra/docker-compose.yml --profile full run --rm --no-deps debezium-init",
  ]);
  assert.match(await readFile(file, "utf8"), /provision:complete/);
});

test("provisionStack dry-run never executes docker", async () => {
  const file = await traceFile();
  let executed = false;
  await provisionStack({
    exec: async () => {
      executed = true;
      return { stdout: "", stderr: "" };
    },
    composeFile: "infra/docker-compose.yml",
    dryRun: true,
    traceFile: file,
    logger: silentLogger(),
  });
  assert.equal(executed, false);
  assert.match(await readFile(file, "utf8"), /provision:kafka-init/);
});

test("seedStack creates and verifies a tenant", async () => {
  const file = await traceFile();
  const seen = [];
  await seedStack({
    fetchImpl: async (url, init) => {
      seen.push({ url, body: JSON.parse(init.body) });
      return url.endsWith("/api/v1/tenants")
        ? response({ status: 201 })
        : response({ status: 200 });
    },
    traceFile: file,
    logger: silentLogger(),
  });
  assert.deepEqual(seen.map((item) => item.url), [
    "http://localhost:8080/api/v1/tenants",
    "http://localhost:8080/api/v1/auth/login",
  ]);
  assert.equal(seen[0].body.slug, SEED.slug);
  const trace = await readFile(file, "utf8");
  assert.match(trace, /seed:created/);
  assert.match(trace, /seed:verified/);
});

test("seedStack treats 409 as an idempotent no-op and still verifies login", async () => {
  const file = await traceFile();
  await seedStack({
    fetchImpl: async (url) =>
      url.endsWith("/api/v1/tenants") ? response({ status: 409 }) : response({ status: 200 }),
    traceFile: file,
    logger: silentLogger(),
  });
  assert.match(await readFile(file, "utf8"), /seed:exists/);
});

test("seedStack fails on an unexpected registration status", async () => {
  const file = await traceFile();
  await assert.rejects(
    () =>
      seedStack({
        fetchImpl: async () => response({ status: 500 }),
        traceFile: file,
        logger: silentLogger(),
      }),
    /Seeding tenant failed with status 500/,
  );
  assert.match(await readFile(file, "utf8"), /seed:failed/);
});

test("seedStack fails when the seeded tenant cannot log in", async () => {
  const file = await traceFile();
  await assert.rejects(
    () =>
      seedStack({
        fetchImpl: async (url) =>
          url.endsWith("/api/v1/tenants") ? response({ status: 201 }) : response({ ok: false, status: 401 }),
        traceFile: file,
        logger: silentLogger(),
      }),
    /Seeded tenant login failed with status 401/,
  );
  assert.match(await readFile(file, "utf8"), /seed:login-failed/);
});

test("up performs the port check, compose up, health wait, provisioning and seed", async () => {
  const file = await traceFile();
  const commands = [];
  await up({
    exec: async (command, args) => {
      commands.push([command, ...args].join(" "));
      return { stdout: "", stderr: "" };
    },
    composeFile: "infra/docker-compose.yml",
    fetchImpl: async (url) =>
      url.endsWith("/api/v1/tenants") ? response({ status: 201 }) : response({ ok: true }),
    sleep: async () => {},
    traceFile: file,
    logger: silentLogger(),
  });
  assert.match(commands[0], /^docker ps --format/);
  assert.match(commands[1], /docker compose -f infra\/docker-compose.yml --profile full up -d --build --wait/);
  const trace = await readFile(file, "utf8");
  for (const expected of ["up:start", "up:port-checked", "up:compose", "provision:complete", "seed:verified", "up:complete"]) {
    assert.ok(trace.includes(expected), `missing ${expected}`);
  }
});

test("up dry-run plans everything and executes nothing", async () => {
  const file = await traceFile();
  let executed = false;
  await up({
    exec: async () => {
      executed = true;
      return { stdout: "", stderr: "" };
    },
    composeFile: "infra/docker-compose.yml",
    dryRun: true,
    traceFile: file,
    logger: silentLogger(),
  });
  assert.equal(executed, false);
  const trace = await readFile(file, "utf8");
  assert.match(trace, /up:compose/);
  assert.match(trace, /up:dry-run/);
});

test("up honours skip flags so operators can restart without reseeding", async () => {
  const file = await traceFile();
  const commands = [];
  await up({
    exec: async (command, args) => {
      commands.push([command, ...args].join(" "));
      return { stdout: "", stderr: "" };
    },
    composeFile: "infra/docker-compose.yml",
    fetchImpl: async () => response({ ok: true }),
    sleep: async () => {},
    skipPortCheck: true,
    skipProvision: true,
    skipSeed: true,
    traceFile: file,
    logger: silentLogger(),
  });
  assert.equal(commands.some((command) => command.includes("run --rm")), false);
  assert.equal(commands[0].startsWith("docker compose"), true);
});

test("down keeps volumes; reset removes them and restarts", async () => {
  const commands = [];
  const logger = silentLogger();
  await down({
    exec: async (command, args) => {
      commands.push([command, ...args].join(" "));
      return { stdout: "", stderr: "" };
    },
    composeFile: "infra/docker-compose.yml",
    traceFile: null,
    logger,
  });
  assert.match(commands[0], / down --remove-orphans$/);

  let restarted = false;
  await reset({
    exec: async (command, args) => {
      commands.push([command, ...args].join(" "));
      return { stdout: "", stderr: "" };
    },
    composeFile: "infra/docker-compose.yml",
    traceFile: null,
    logger,
    upFn: async () => {
      restarted = true;
    },
  });
  assert.match(commands[1], / down -v --remove-orphans$/);
  assert.equal(restarted, true);
});

test("parseArgs reads every documented option", () => {
  const options = parseArgs([
    "up",
    "--dry-run",
    "--skip-port-check",
    "--profile",
    "minimal",
    "--compose-file",
    "custom.yml",
    "--api-url",
    "http://example.test",
  ]);
  assert.equal(options.command, "up");
  assert.equal(options.dryRun, true);
  assert.equal(options.skipPortCheck, true);
  assert.equal(options.profile, "minimal");
  assert.equal(options.composeFile, "custom.yml");
  assert.equal(options.baseUrl, "http://example.test");
});
