#!/usr/bin/env node
/**
 * Fails when a JaCoCo XML report is missing or empty (issue #182).
 *
 * SonarQube treats a missing coverage report as "no coverage" rather than an
 * error, which can make a green quality gate misleading. The CI job runs this
 * check before the scanner so a broken report fails fast with an actionable
 * message.
 *
 * Usage: node scripts/check-jacoco-report.mjs <report.xml> [more.xml ...]
 */

import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

/** Returns an error message when the report is missing/empty, otherwise undefined. */
export function validateJacocoReport(xml, path = "jacoco.xml") {
  if (typeof xml !== "string" || xml.trim() === "") {
    return `${path} is empty or unreadable.`;
  }
  if (!/<report\b/.test(xml)) {
    return `${path} is not a JaCoCo XML report (missing <report>).`;
  }
  if (!/<(package|class|sourcefile)\b/.test(xml)) {
    return `${path} contains no coverage entries.`;
  }
  return undefined;
}

export function main(paths, log = console.log) {
  if (paths.length === 0) {
    log("Usage: node scripts/check-jacoco-report.mjs <report.xml> [more.xml ...]");
    return 2;
  }
  let failed = false;
  for (const path of paths) {
    let xml = "";
    try {
      xml = readFileSync(path, "utf8");
    } catch {
      log(`FAIL: ${path} does not exist. Ensure 'mvn jacoco:report' ran before Sonar.`);
      failed = true;
      continue;
    }
    const error = validateJacocoReport(xml, path);
    if (error) {
      log(`FAIL: ${error}`);
      failed = true;
    } else {
      log(`OK: ${path} contains JaCoCo coverage data.`);
    }
  }
  return failed ? 1 : 0;
}

const invokedDirectly =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedDirectly) {
  process.exitCode = main(process.argv.slice(2));
}
