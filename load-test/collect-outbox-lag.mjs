// Collects real outbox publish-lag samples from the Docker Compose stack (issue #159).
//
// Polling samples are read from Postgres: `published_at - created_at` for rows the relay drained.
// CDC samples are computed by joining Debezium's Kafka records (EventRouter `id` header and Kafka
// record timestamp) to the same outbox rows.
//
// Usage:
//   node load-test/collect-outbox-lag.mjs polling --output load-test/results/polling.json
//   node load-test/collect-outbox-lag.mjs cdc --output load-test/results/cdc.json
//   node load-test/collect-outbox-lag.mjs both --output-dir load-test/results --wait-drain
//
// The stack is expected to be running (see load-test/README.md). The CDC connector is optional;
// when it is not registered the CDC document records that fact instead of failing.
import { execFile } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import {
  computeCdcLags,
  parseConsoleConsumerRecords,
  parseOutboxRows,
  parsePsqlSamples,
} from "./lib/cdc-benchmark.mjs";

const execFileAsync = promisify(execFile);
const ROOT = fileURLToPath(new URL("../", import.meta.url));

function usage() {
  return `usage: node load-test/collect-outbox-lag.mjs [polling|cdc|both] [options]

options:
  --output <path>           write a single mode's samples to this path
  --output-dir <dir>        directory for polling.json/cdc.json in "both" mode
  --window-seconds <n>      look back this many seconds (default 900)
  --limit <n>               maximum outbox rows to sample (default 5000)
  --wait-drain              wait until the polling relay has no unpublished rows
  --wait-timeout-ms <n>     drain wait budget (default 60000)
  --cdc-wait-ms <n>         wait after generating events before reading CDC (default 3000)
  --kafka-timeout-ms <n>    console-consumer idle timeout (default 15000)
  --compose-file <path>     Compose file (default infra/docker-compose.yml)
  --compose-project <name>  Compose project name (default COMPOSE_PROJECT_NAME)
  --connect-url <url>       Kafka Connect REST endpoint (default http://localhost:8083)
  --connector-name <name>   connector name (default awap-outbox-connector)
  --tenant-id <uuid>        only sample this tenant (default TENANT_ID env, else all)
  --postgres-service <name> Compose service name (default postgres)
  --kafka-service <name>    Compose service name (default kafka)
  --require-cdc             fail when the connector is absent or emits no samples
  --help                    show this help`;
}

function parseArgs(argv) {
  const options = {
    mode: "both",
    output: null,
    outputDir: "load-test/results",
    windowSeconds: 900,
    limit: 5000,
    waitDrain: false,
    waitTimeoutMs: 60_000,
    cdcWaitMs: 3_000,
    kafkaTimeoutMs: 15_000,
    composeFile: "infra/docker-compose.yml",
    composeProject: process.env.COMPOSE_PROJECT_NAME ?? null,
    connectUrl: process.env.CONNECT_URL ?? "http://localhost:8083",
    connectorName: process.env.CONNECTOR_NAME ?? "awap-outbox-connector",
    tenantId: process.env.TENANT_ID ?? "",
    postgresService: process.env.POSTGRES_SERVICE ?? "postgres",
    kafkaService: process.env.KAFKA_SERVICE ?? "kafka",
    postgresUser: process.env.POSTGRES_USER ?? "awap",
    postgresDb: process.env.POSTGRES_DB ?? "awap",
    requireCdc: false,
  };

  const valueFlags = {
    "--output": "output",
    "--output-dir": "outputDir",
    "--window-seconds": "windowSeconds",
    "--limit": "limit",
    "--wait-timeout-ms": "waitTimeoutMs",
    "--cdc-wait-ms": "cdcWaitMs",
    "--kafka-timeout-ms": "kafkaTimeoutMs",
    "--compose-file": "composeFile",
    "--compose-project": "composeProject",
    "--connect-url": "connectUrl",
    "--connector-name": "connectorName",
    "--tenant-id": "tenantId",
    "--postgres-service": "postgresService",
    "--kafka-service": "kafkaService",
  };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--help" || argument === "-h") {
      options.help = true;
    } else if (argument === "--wait-drain") {
      options.waitDrain = true;
    } else if (argument === "--require-cdc") {
      options.requireCdc = true;
    } else if (valueFlags[argument]) {
      const value = argv[index + 1];
      if (value === undefined) {
        throw new Error(`${argument} requires a value`);
      }
      options[valueFlags[argument]] = value;
      index += 1;
    } else if (!argument.startsWith("--") && ["polling", "cdc", "both"].includes(argument)) {
      options.mode = argument;
    } else {
      throw new Error(`unknown argument: ${argument}`);
    }
  }

  for (const key of ["windowSeconds", "limit", "waitTimeoutMs", "cdcWaitMs", "kafkaTimeoutMs"]) {
    options[key] = Number.parseInt(options[key], 10);
    if (!Number.isFinite(options[key]) || options[key] < 0) {
      throw new Error(`${key} must be a non-negative integer`);
    }
  }
  return options;
}

function composeArgs(options, args) {
  const result = ["compose"];
  if (options.composeProject) {
    result.push("-p", options.composeProject);
  }
  result.push("-f", options.composeFile, ...args);
  return result;
}

async function runCompose(options, args, { allowFailure = false } = {}) {
  try {
    const { stdout, stderr } = await execFileAsync("docker", composeArgs(options, args), {
      cwd: ROOT,
      maxBuffer: 64 * 1024 * 1024,
    });
    return { stdout, stderr, code: 0 };
  } catch (error) {
    if (allowFailure) {
      return {
        stdout: error.stdout ?? "",
        stderr: error.stderr ?? error.message,
        code: typeof error.code === "number" ? error.code : 1,
      };
    }
    throw new Error(
      `docker ${composeArgs(options, args).join(" ")} failed: ${error.stderr ?? error.message}`,
    );
  }
}

async function psql(options, sql, { allowFailure = false } = {}) {
  return runCompose(
    options,
    [
      "exec",
      "-T",
      options.postgresService,
      "psql",
      "-U",
      options.postgresUser,
      "-d",
      options.postgresDb,
      "-t",
      "-A",
      "-F",
      "\t",
      "-c",
      sql,
    ],
    { allowFailure },
  );
}

function sleep(ms) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function tenantPredicate(options) {
  if (!options.tenantId) {
    return "";
  }
  if (!UUID.test(options.tenantId)) {
    throw new Error("--tenant-id must be a UUID");
  }
  return `AND tenant_id = '${options.tenantId}'`;
}

async function waitForDrain(options) {
  const deadline = Date.now() + options.waitTimeoutMs;
  let pending = null;
  while (Date.now() <= deadline) {
    const result = await psql(
      options,
      `SELECT count(*) FROM outbox_events WHERE published = false ${tenantPredicate(options)};`,
      { allowFailure: true },
    );
    pending = Number.parseInt(result.stdout.trim(), 10);
    if (result.code === 0 && pending === 0) {
      return 0;
    }
    await sleep(1_000);
  }
  throw new Error(
    `polling relay did not drain the outbox within ${options.waitTimeoutMs}ms (last pending=${pending}); ` +
      "is KAFKA_ENABLED=true and the backend healthy?",
  );
}

async function collectPolling(options) {
  const pendingAfterWait = options.waitDrain ? await waitForDrain(options) : null;
  const sql = `
    SELECT round(EXTRACT(EPOCH FROM (published_at - created_at)) * 1000)::bigint
    FROM outbox_events
    WHERE published = true
      AND published_at IS NOT NULL
      AND created_at >= now() - make_interval(secs => ${options.windowSeconds})
      ${tenantPredicate(options)}
    ORDER BY created_at ASC
    LIMIT ${options.limit};
  `;
  const { stdout } = await psql(options, sql);
  const samples = parsePsqlSamples(stdout);
  if (samples.length === 0) {
    throw new Error(
      "no polling samples found; generate outbox events and ensure the relay published them " +
        "(KAFKA_ENABLED=true, healthy backend)",
    );
  }
  return {
    mode: "polling",
    source: "postgres:outbox_events.published_at-created_at",
    tenantId: options.tenantId || null,
    windowSeconds: options.windowSeconds,
    limit: options.limit,
    pendingAfterWait,
    samples,
    collectedAt: new Date().toISOString(),
  };
}

async function readConnectorStatus(options) {
  const base = options.connectUrl.replace(/\/+$/, "");
  try {
    const response = await fetch(`${base}/connectors/${options.connectorName}/status`, {
      signal: AbortSignal.timeout(5_000),
    });
    if (!response.ok) {
      return {
        registered: false,
        reason: `Connect returned HTTP ${response.status} for ${options.connectorName}`,
      };
    }
    const body = await response.json();
    return {
      registered: true,
      connectorState: body.connector?.state ?? "UNKNOWN",
      taskStates: (body.tasks ?? []).map((task) => task.state),
    };
  } catch (error) {
    return {
      registered: false,
      reason: `Kafka Connect is unreachable at ${options.connectUrl}: ${error.message}`,
    };
  }
}

async function collectCdc(options) {
  const status = await readConnectorStatus(options);
  if (!status.registered) {
    return {
      mode: "cdc",
      registered: false,
      connectorState: null,
      reason: status.reason,
      samples: [],
      collectedAt: new Date().toISOString(),
    };
  }

  const rowsSql = `
    SELECT id, round(EXTRACT(EPOCH FROM created_at) * 1000)::bigint
    FROM outbox_events
    WHERE created_at >= now() - make_interval(secs => ${options.windowSeconds})
      ${tenantPredicate(options)}
    ORDER BY created_at ASC
    LIMIT ${options.limit};
  `;
  const { stdout: rowsOutput } = await psql(options, rowsSql);
  const outboxRows = parseOutboxRows(rowsOutput);

  if (options.cdcWaitMs > 0) {
    await sleep(options.cdcWaitMs);
  }

  const maxMessages = Math.max(1_000, outboxRows.length * 2);
  const consumer = await runCompose(
    options,
    [
      "exec",
      "-T",
      options.kafkaService,
      "kafka-console-consumer",
      "--bootstrap-server",
      "localhost:9092",
      "--include",
      "^outbox\\.event\\..*",
      "--from-beginning",
      "--timeout-ms",
      String(options.kafkaTimeoutMs),
      "--max-messages",
      String(maxMessages),
      "--property",
      "print.timestamp=true",
      "--property",
      "print.headers=true",
    ],
    { allowFailure: true },
  );

  const records = parseConsoleConsumerRecords(consumer.stdout);
  const lags = computeCdcLags(outboxRows, records);
  const result = {
    mode: "cdc",
    registered: true,
    connectorState: status.connectorState,
    taskStates: status.taskStates,
    topicPattern: "^outbox\\.event\\..*",
    tenantId: options.tenantId || null,
    windowSeconds: options.windowSeconds,
    outboxRows: outboxRows.length,
    kafkaRecords: records.length,
    ...lags,
    samples: lags.samples,
    collectedAt: new Date().toISOString(),
  };

  if (lags.samples.length === 0 && consumer.code !== 0) {
    result.reason = `kafka-console-consumer exited ${consumer.code}: ${consumer.stderr.slice(0, 500)}`;
  }
  return result;
}

function pathForMode(options, mode) {
  if (options.output) {
    return resolve(ROOT, options.output);
  }
  return resolve(ROOT, options.outputDir, `${mode}.json`);
}

async function writeJson(path, value) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    console.log(usage());
    return;
  }

  const documents = {};
  if (options.mode === "polling" || options.mode === "both") {
    documents.polling = await collectPolling(options);
  }
  if (options.mode === "cdc" || options.mode === "both") {
    documents.cdc = await collectCdc(options);
    if (options.requireCdc && documents.cdc.samples.length === 0) {
      throw new Error(
        `CDC was required but produced no samples (${documents.cdc.reason ?? "no matching Kafka records"})`,
      );
    }
  }

  if (options.mode === "both") {
    await writeJson(resolve(ROOT, options.outputDir, "polling.json"), documents.polling);
    await writeJson(resolve(ROOT, options.outputDir, "cdc.json"), documents.cdc);
    console.log(
      `polling samples=${documents.polling.samples.length}; ` +
        `cdc samples=${documents.cdc.samples.length} (registered=${documents.cdc.registered}) ` +
        `-> ${options.outputDir}`,
    );
    return;
  }

  const path = pathForMode(options, options.mode);
  await writeJson(path, documents[options.mode]);
  console.log(
    `${options.mode} samples=${documents[options.mode].samples.length} ` +
      `-> ${options.output ?? path}`,
  );
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
