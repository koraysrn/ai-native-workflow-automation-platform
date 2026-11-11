// Evaluates a k6 summary against the tenancy SLO catalog and publishes the benchmark (issue #201).
//
// Usage:
//   node load-test/report-slo.mjs \
//     --summary load-test/results/tenancy-summary.json \
//     --tenants 1000 --mode full \
//     --output load-test/results/tenancy-slo-report.json \
//     --step-summary "$GITHUB_STEP_SUMMARY"
//
// Exits 1 when any SLO regresses, 2 on usage errors, 0 when every budget holds.
import { readFile, writeFile, appendFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { buildReport, DRY_RUN_SUMMARY, renderSummaryMarkdown } from "./lib/slo.mjs";

export const USAGE = `usage: node load-test/report-slo.mjs [options]

  --summary <path>        k6 --summary-export document (required unless --dry-run)
  --output <path>         write the JSON report here
  --step-summary <path>   append the markdown table (e.g. $GITHUB_STEP_SUMMARY)
  --tenants <n>           seeded tenant count recorded in the report (default TENANT_COUNT or 1000)
  --api-base <url>        API base URL recorded in the report
  --mode <full|smoke>     profile label (default full)
  --dry-run               evaluate the built-in sample summary, no file input required
  --no-fail               always exit 0 (reporting only)
  --help                  print this help`;

function valueOf(flag, raw) {
  if (raw === undefined || raw.startsWith("--")) {
    throw new Error(`${flag} expects a value`);
  }
  return raw;
}

export function parseReportArgs(argv) {
  const options = {
    help: false,
    dryRun: false,
    failOnRegression: true,
    summary: null,
    output: null,
    stepSummary: null,
    tenants: Number.parseInt(process.env.TENANT_COUNT ?? "1000", 10),
    apiBase: process.env.API_BASE ?? "http://localhost:8080",
    mode: "full",
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    switch (arg) {
      case "--help":
        options.help = true;
        break;
      case "--dry-run":
        options.dryRun = true;
        break;
      case "--no-fail":
        options.failOnRegression = false;
        break;
      case "--summary":
        index += 1;
        options.summary = valueOf(arg, argv[index]);
        break;
      case "--output":
        index += 1;
        options.output = valueOf(arg, argv[index]);
        break;
      case "--step-summary":
        index += 1;
        options.stepSummary = valueOf(arg, argv[index]);
        break;
      case "--tenants":
        index += 1;
        options.tenants = Number.parseInt(valueOf(arg, argv[index]), 10);
        break;
      case "--api-base":
        index += 1;
        options.apiBase = valueOf(arg, argv[index]);
        break;
      case "--mode":
        index += 1;
        options.mode = valueOf(arg, argv[index]);
        break;
      default:
        throw new Error(`unknown argument "${arg}"`);
    }
  }

  if (options.help) {
    return options;
  }
  if (!options.dryRun && !options.summary) {
    throw new Error("--summary is required unless --dry-run is set");
  }
  if (!Number.isInteger(options.tenants) || options.tenants < 1) {
    throw new Error("--tenants expects a positive integer");
  }
  if (!["full", "smoke"].includes(options.mode) && !options.dryRun) {
    throw new Error('--mode must be "full" or "smoke"');
  }
  return options;
}

/** Testable entry point. */
export async function main(argv = process.argv.slice(2), deps = {}) {
  const log = deps.log ?? ((line) => console.log(line));
  const read = deps.readFile ?? readFile;
  const write = deps.writeFile ?? writeFile;
  const append = deps.appendFile ?? appendFile;

  let options;
  try {
    options = parseReportArgs(argv);
  } catch (error) {
    log(`error: ${error.message}\n\n${USAGE}`);
    return 2;
  }

  if (options.help) {
    log(USAGE);
    return 0;
  }

  let summary;
  if (options.dryRun) {
    summary = DRY_RUN_SUMMARY;
  } else {
    try {
      summary = JSON.parse(await read(options.summary, "utf8"));
    } catch (error) {
      log(`error: cannot read the k6 summary ${options.summary}: ${error.message}`);
      return 2;
    }
  }

  const report = buildReport({
    summary,
    mode: options.dryRun ? "dry-run" : options.mode,
    tenants: options.tenants,
    apiBase: options.apiBase,
    source: options.dryRun ? "built-in-sample" : options.summary,
  });
  const markdown = renderSummaryMarkdown(report);

  if (options.output) {
    await write(options.output, `${JSON.stringify(report, null, 2)}\n`);
  }
  if (options.stepSummary) {
    await append(options.stepSummary, markdown);
  } else {
    log(markdown);
  }

  log(
    report.result === "pass"
      ? `SLO result: pass (${report.checks.length} checks)`
      : `SLO result: fail (${report.regressions.length} regression(s)): ` +
          report.regressions.map((regression) => regression.id).join(", "),
  );

  if (report.result !== "pass" && options.failOnRegression) {
    return 1;
  }
  return 0;
}

const invokedDirectly =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (invokedDirectly) {
  main()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error) => {
      console.error(error);
      process.exitCode = 1;
    });
}
