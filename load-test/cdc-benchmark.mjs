// Compares measured outbox publish lag for the polling relay and the Debezium connector and prints
// the cutover verdict using the same rules as the runbook (issues #131 and #159).
//
// The sample files are produced from the Compose stack by collect-outbox-lag.mjs:
//   node load-test/collect-outbox-lag.mjs both --output-dir load-test/results --wait-drain
//   node load-test/cdc-benchmark.mjs load-test/results/polling.json load-test/results/cdc.json \
//     --output load-test/results/cdc-benchmark.json
//
// Each file is {"samples": [lagMs, ...]}. CDC is optional: without a connector the report still
// contains the polling p50/p95/max and the evaluateCutover() verdict.
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { buildBenchmarkReport } from "./lib/cdc-benchmark.mjs";

const ROOT = fileURLToPath(new URL("../", import.meta.url));

function usage() {
  return `usage: node load-test/cdc-benchmark.mjs <polling.json> [cdc.json] [options]

options:
  --output <path>      also write the benchmark report as JSON
  --require-cdc        fail when CDC was not registered or produced no samples
  --fail-on-cutover    fail when the polling measurements do NOT justify a CDC cutover
  --help               show this help

cutover inputs read from the environment:
  BACKLOG_SWEEPS, SLOT_LAG_BYTES, WAL_GROWTH_BYTES_PER_MIN`;
}

function parseArgs(argv) {
  const options = {
    polling: null,
    cdc: null,
    output: null,
    requireCdc: false,
    failOnCutover: false,
    help: false,
  };
  const positional = [];

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--help" || argument === "-h") {
      options.help = true;
    } else if (argument === "--require-cdc") {
      options.requireCdc = true;
    } else if (argument === "--fail-on-cutover") {
      options.failOnCutover = true;
    } else if (argument === "--output") {
      options.output = argv[index + 1];
      if (!options.output) {
        throw new Error("--output requires a path");
      }
      index += 1;
    } else if (argument.startsWith("--")) {
      throw new Error(`unknown argument: ${argument}`);
    } else {
      positional.push(argument);
    }
  }

  [options.polling, options.cdc] = positional;
  return options;
}

async function readJson(path) {
  return JSON.parse(await readFile(resolve(ROOT, path), "utf8"));
}

async function readCdc(path) {
  if (!path) {
    return { registered: false, reason: "no CDC sample file supplied", samples: [] };
  }
  try {
    return await readJson(path);
  } catch (error) {
    if (error.code === "ENOENT") {
      return { registered: false, reason: `${path} does not exist`, samples: [] };
    }
    throw error;
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help || !options.polling) {
    console.log(usage());
    process.exit(options.help ? 0 : 2);
  }

  const report = buildBenchmarkReport({
    polling: await readJson(options.polling),
    cdc: await readCdc(options.cdc),
    cutoverInputs: {
      consecutiveBacklogSweeps: process.env.BACKLOG_SWEEPS ?? 0,
      slotLagBytes: process.env.SLOT_LAG_BYTES ?? 0,
      walGrowthBytesPerMin: process.env.WAL_GROWTH_BYTES_PER_MIN ?? 0,
    },
    sources: {
      polling: options.polling,
      cdc: options.cdc,
    },
    environment: {
      composeProject: process.env.COMPOSE_PROJECT_NAME || null,
      relayIntervalMs:
        Number.parseInt(process.env.OUTBOX_RELAY_INTERVAL_MS ?? "0", 10) || null,
      kafkaEnabled: process.env.KAFKA_ENABLED ?? null,
    },
  });

  console.log(JSON.stringify(report, null, 2));

  if (options.output) {
    const outputPath = resolve(ROOT, options.output);
    await mkdir(dirname(outputPath), { recursive: true });
    await writeFile(outputPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  }

  if (options.requireCdc && (!report.cdc.registered || report.cdc.count === 0)) {
    console.error(
      `CDC was required but produced no measured samples (${report.cdc.reason ?? "no matching records"}).`,
    );
    process.exit(2);
  }

  if (options.failOnCutover && !report.verdict.cutover) {
    console.error("Expected the polling measurements to justify a CDC cutover, but no trigger fired.");
    process.exit(1);
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exit(2);
});
