#!/usr/bin/env node
// One-command local stack lifecycle (issue #189).
//
//   make up        -> up (full profile, port check, health wait, provisioning, seed)
//   make down      -> down (stops the full profile, keeps volumes)
//   make reset     -> reset (down -v, then a clean up)
//   make seed      -> seed (idempotent tenant + login smoke test)
//   make provision -> provision (idempotent buckets, Kafka topics, Debezium connector)
//
// Every command supports `--dry-run`, which prints the planned work and records it in
// AWAP_PROVISION_TRACE_FILE. The node:test suite drives the exported functions with injected
// exec/fetch/sleep fakes and asserts every branch, including every error path.

import { execFile } from "node:child_process";
import { appendFile } from "node:fs/promises";
import process from "node:process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

export const DEFAULT_PORTS = [
  3000, 3001, 3310, 5432, 6379, 8000, 8080, 8081, 8083, 8200, 9000, 9001, 9090, 9092, 9200,
];

export const HEALTH_ENDPOINTS = {
  backend: "http://localhost:8080/actuator/health/readiness",
  frontend: "http://localhost:3000",
  "schema-registry": "http://localhost:8081/subjects",
  prometheus: "http://localhost:9090/-/ready",
  grafana: "http://localhost:3001/api/health",
  minio: "http://localhost:9000/minio/health/live",
  "ai-layer": "http://localhost:8000/api/v1/health",
};

export const SEED = {
  slug: "awap-local",
  name: "AWAP Local",
  adminEmail: "admin@awap.local",
  adminPassword: "Local-Stack-123!",
  adminFullName: "Local Admin",
};

export async function trace(file, line) {
  if (!file) {
    return;
  }
  await appendFile(file, `${line}\n`, "utf8");
}

/** Runs a command, or only records it in dry-run mode. */
export async function runCommand({
  exec,
  command,
  args,
  cwd,
  env,
  dryRun = false,
  traceFile,
  traceLine,
  logger = console,
}) {
  if (dryRun) {
    await trace(traceFile, traceLine ?? `dry-run:${command}`);
    logger.log(`[dry-run] ${command} ${args.join(" ")}`);
    return { stdout: "", stderr: "" };
  }
  const result = await exec(command, args, { cwd, env });
  await trace(traceFile, traceLine ?? `executed:${command}`);
  return result;
}

/**
 * Parses `docker ps --format {{json .}}` output and returns the host ports bound by containers
 * that do not belong to this Compose project. A port conflict on any of them is a hard error
 * before the stack is declared ready.
 */
export function findPortConflicts(psOutput, ports = DEFAULT_PORTS, project = "infra") {
  const conflicts = [];
  for (const line of psOutput.split("\n")) {
    const trimmed = line.trim();
    if (trimmed.length === 0) {
      continue;
    }
    let container;
    try {
      container = JSON.parse(trimmed);
    } catch {
      continue;
    }
    const names = String(container.Names ?? "");
    if (names.startsWith(project)) {
      continue;
    }
    for (const port of ports) {
      // Docker renders "0.0.0.0:8080->8080/tcp", "[::]:8080->8080/tcp" and "8080->8080/tcp".
      const pattern = new RegExp(`(^|,)(0\\.0\\.0\\.0:|\\[::\\]:)${port}->|(^|,)${port}->`);
      if (pattern.test(String(container.Ports ?? ""))) {
        conflicts.push({ port, container: names });
      }
    }
  }
  return conflicts;
}

/** Port preflight. Fails with the conflicting port and container name. */
export async function assertPortsFree({
  exec,
  ports = DEFAULT_PORTS,
  project = process.env.COMPOSE_PROJECT_NAME ?? "infra",
  traceFile,
  logger = console,
}) {
  const { stdout } = await exec("docker", ["ps", "--format", "{{json .}}"], {});
  const conflicts = findPortConflicts(stdout, ports, project);
  if (conflicts.length > 0) {
    const detail = conflicts.map((item) => `${item.port} (${item.container})`).join(", ");
    await trace(traceFile, "up:port-conflict");
    throw new Error(`Port conflict detected before startup: ${detail}`);
  }
  await trace(traceFile, "up:port-checked");
  logger.log("No port conflicts detected.");
}

/** Polls one endpoint until it answers 2xx or the bounded retry budget is exhausted. */
export async function waitForEndpoint({
  name,
  url,
  fetchImpl = fetch,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  attempts = 60,
  intervalMs = 5000,
  logger = console,
  traceFile,
}) {
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetchImpl(url, { signal: AbortSignal.timeout(5000) });
      if (response.ok) {
        await trace(traceFile, `wait:ready:${name}`);
        logger.log(`${name} is ready after ${attempt} attempt(s).`);
        return;
      }
    } catch {
      // A dependency that is still starting refuses connections; retry until the budget ends.
    }
    if (attempt < attempts) {
      await sleep(intervalMs);
    }
  }
  await trace(traceFile, `wait:timeout:${name}`);
  throw new Error(`${name} did not become ready within ${attempts} attempts (${url})`);
}

/** Waits for every documented endpoint of the active profile. */
export async function waitForStack({
  profile = "full",
  fetchImpl = fetch,
  sleep,
  attempts = 60,
  intervalMs = 5000,
  healthEndpoints = HEALTH_ENDPOINTS,
  traceFile,
  logger = console,
}) {
  const names = ["backend", "frontend"];
  if (profile === "full") {
    names.push("schema-registry", "prometheus", "grafana", "minio", "ai-layer");
  }
  for (const name of names) {
    await waitForEndpoint({
      name,
      url: healthEndpoints[name],
      fetchImpl,
      sleep,
      attempts,
      intervalMs,
      traceFile,
      logger,
    });
  }
}

/** Runs the idempotent provisioners after the stack is healthy. */
export async function provisionStack({
  exec,
  composeFile,
  profile = "full",
  dryRun = false,
  traceFile,
  logger = console,
  services = ["kafka-init", "minio-init", "debezium-init"],
}) {
  for (const service of services) {
    await runCommand({
      exec,
      command: "docker",
      args: ["compose", "-f", composeFile, "--profile", profile, "run", "--rm", "--no-deps", service],
      dryRun,
      traceFile,
      traceLine: `provision:${service}`,
      logger,
    });
  }
  await trace(traceFile, "provision:complete");
}

/**
 * Idempotent seed: registration is keyed by a deterministic slug. A 201 creates the tenant,
 * a 409 proves it already exists, and both are followed by a login smoke test.
 */
export async function seedStack({
  baseUrl = "http://localhost:8080",
  fetchImpl = fetch,
  seed = SEED,
  traceFile,
  logger = console,
}) {
  const register = await fetchImpl(`${baseUrl}/api/v1/tenants`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(seed),
  });
  if (register.status === 201) {
    await trace(traceFile, "seed:created");
    logger.log(`Seeded tenant ${seed.slug}.`);
  } else if (register.status === 409) {
    await trace(traceFile, "seed:exists");
    logger.log(`Tenant ${seed.slug} already exists; skipping creation.`);
  } else {
    await trace(traceFile, "seed:failed");
    throw new Error(`Seeding tenant failed with status ${register.status}`);
  }

  const login = await fetchImpl(`${baseUrl}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      tenantSlug: seed.slug,
      email: seed.adminEmail,
      password: seed.adminPassword,
    }),
  });
  if (!login.ok) {
    await trace(traceFile, "seed:login-failed");
    throw new Error(`Seeded tenant login failed with status ${login.status}`);
  }
  await trace(traceFile, "seed:verified");
  logger.log(`Seed verified: ${seed.slug} / ${seed.adminEmail}`);
}

/** `up`: preflight, compose up, wait for health, provision and seed. */
export async function up({
  exec,
  composeFile,
  project,
  profile = "full",
  dryRun = false,
  skipPortCheck = false,
  skipSeed = false,
  skipProvision = false,
  fetchImpl = fetch,
  sleep,
  traceFile,
  logger = console,
}) {
  await trace(traceFile, "up:start");
  if (!skipPortCheck && !dryRun) {
    await assertPortsFree({ exec, project, traceFile, logger });
  }
  await runCommand({
    exec,
    command: "docker",
    args: ["compose", "-f", composeFile, "--profile", profile, "up", "-d", "--build", "--wait"],
    dryRun,
    traceFile,
    traceLine: "up:compose",
    logger,
  });
  if (!dryRun) {
    await waitForStack({ profile, fetchImpl, sleep, traceFile, logger });
    if (!skipProvision) {
      await provisionStack({ exec, composeFile, profile, dryRun, traceFile, logger });
    }
    if (!skipSeed) {
      await seedStack({ fetchImpl, traceFile, logger });
    }
  } else {
    await trace(traceFile, "up:dry-run");
  }
  await trace(traceFile, "up:complete");
}

/** `down`: stop the full profile but keep volumes so the next up is fast. */
export async function down({ exec, composeFile, profile = "full", dryRun = false, traceFile, logger = console }) {
  await runCommand({
    exec,
    command: "docker",
    args: ["compose", "-f", composeFile, "--profile", profile, "down", "--remove-orphans"],
    dryRun,
    traceFile,
    traceLine: "down:compose",
    logger,
  });
  await trace(traceFile, "down:complete");
}

/** `reset`: remove volumes (stale-state recovery) and start a clean seeded stack. */
export async function reset({ exec, composeFile, profile = "full", dryRun = false, traceFile, logger = console, upFn = up }) {
  await runCommand({
    exec,
    command: "docker",
    args: ["compose", "-f", composeFile, "--profile", profile, "down", "-v", "--remove-orphans"],
    dryRun,
    traceFile,
    traceLine: "reset:down",
    logger,
  });
  await upFn({ exec, composeFile, profile, dryRun, traceFile, logger });
  await trace(traceFile, "reset:complete");
}

export function parseArgs(argv) {
  const options = {
    command: argv[0] ?? "help",
    dryRun: argv.includes("--dry-run"),
    skipPortCheck: argv.includes("--skip-port-check"),
    skipSeed: argv.includes("--skip-seed"),
    skipProvision: argv.includes("--skip-provision"),
    profile: "full",
    composeFile: "infra/docker-compose.yml",
    baseUrl: "http://localhost:8080",
  };
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (value === "--profile") {
      options.profile = argv[index + 1] ?? options.profile;
    } else if (value === "--compose-file") {
      options.composeFile = argv[index + 1] ?? options.composeFile;
    } else if (value === "--api-url") {
      options.baseUrl = argv[index + 1] ?? options.baseUrl;
    }
  }
  return options;
}

export async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  const exec = async (command, args, execOptions = {}) => {
    const result = await execFileAsync(command, args, { ...execOptions, maxBuffer: 10 * 1024 * 1024 });
    return { stdout: result.stdout, stderr: result.stderr };
  };
  const traceFile = process.env.AWAP_PROVISION_TRACE_FILE;
  const common = {
    exec,
    composeFile: options.composeFile,
    profile: options.profile,
    dryRun: options.dryRun,
    traceFile,
  };
  switch (options.command) {
    case "up":
      await up({
        ...common,
        skipPortCheck: options.skipPortCheck,
        skipSeed: options.skipSeed,
        skipProvision: options.skipProvision,
      });
      return 0;
    case "down":
      await down(common);
      return 0;
    case "reset":
      await reset(common);
      return 0;
    case "provision":
      await provisionStack(common);
      return 0;
    case "seed":
      await seedStack({ baseUrl: options.baseUrl, traceFile });
      return 0;
    case "wait":
      await waitForStack({ profile: options.profile, traceFile });
      return 0;
    default:
      console.log(
        "Usage: node infra/scripts/stack.mjs <up|down|reset|provision|seed|wait> [--dry-run] [--profile full]",
      );
      return options.command === "help" ? 0 : 2;
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
