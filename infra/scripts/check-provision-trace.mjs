#!/usr/bin/env node
// Fails when a provisioning dry-run did not execute every branch (issue #189).
//
// The CI job runs `stack.mjs up --dry-run`, `stack.mjs provision --dry-run` and
// `provision-connector.mjs --dry-run` with AWAP_PROVISION_TRACE_FILE set, then this checker
// proves that every planned branch produced a trace line. A renamed command or an accidentally
// skipped branch therefore fails the job instead of passing silently.

import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

export const REQUIRED_BRANCHES = [
  "up:start",
  "up:dry-run",
  "up:complete",
  "provision:kafka-init",
  "provision:minio-init",
  "provision:debezium-init",
  "provision:complete",
  "connector:dry-run",
];

/** Returns the required branches that the trace does not contain. */
export function missingBranches(traceLines, required = REQUIRED_BRANCHES) {
  const seen = new Set(traceLines.map((line) => line.trim()).filter(Boolean));
  return required.filter((branch) => !seen.has(branch));
}

export async function checkTrace(file, required = REQUIRED_BRANCHES) {
  const content = await readFile(file, "utf8");
  const missing = missingBranches(content.split("\n"), required);
  if (missing.length > 0) {
    throw new Error(`Unexercised provisioning branches: ${missing.join(", ")}`);
  }
  return required.length;
}

async function main(argv = process.argv.slice(2)) {
  const file = argv[0] ?? process.env.AWAP_PROVISION_TRACE_FILE;
  if (!file) {
    console.error("Usage: node infra/scripts/check-provision-trace.mjs <trace-file>");
    return 2;
  }
  try {
    const count = await checkTrace(file);
    console.log(`Every one of the ${count} provisioning branches was exercised.`);
    return 0;
  } catch (error) {
    console.error(error.message);
    return 1;
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  main().then((code) => {
    process.exitCode = code;
  });
}
