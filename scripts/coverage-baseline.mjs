#!/usr/bin/env node
// Issue #197: generate docs/coverage-baseline.md from the JaCoCo reports and
// fail CI when the committed document drifts from the latest measurement.
//
// The generator reads the CSV *and* the XML report of every module, applies the
// same exclusions as the JaCoCo bundle rule, cross-checks the two reports and
// renders a deterministic document. `--check` compares the rendered document
// with the committed one; `--delta` writes the PR-comment table.

import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { pathToFileURL } from "node:url";

import { isExcludedPackage } from "./coverage-trend.mjs";

// Critical classes from the JaCoCo CLASS rules in backend/pom.xml and the
// issue #196 additions listed in docs/coverage-ratchet.md.
export const CRITICAL_CLASSES = Object.freeze({
  "awap-identity": Object.freeze([
    "com.awap.notification.domain.NotificationTemplate",
    "com.awap.notification.domain.NotificationTemplateVersion",
    "com.awap.notification.domain.NotificationTemplateValidator",
    "com.awap.notification.domain.NotificationTemplateLocale",
    "com.awap.notification.application.NotificationTemplateRenderer",
    "com.awap.notification.application.NotificationTemplateService",
    "com.awap.notification.interfaces.api.NotificationTemplateController",
    "com.awap.workflow.application.WorkflowExpressionValidationService",
    "com.awap.workflow.application.WorkflowExpressionValidationMetrics",
    "com.awap.workflow.interfaces.api.WorkflowDefinitionController",
    "com.awap.organization.domain.Position",
    "com.awap.organization.application.OrganizationService",
    "com.awap.ai.application.AiBudgetGuard",
    "com.awap.ai.application.AiNodeService",
    "com.awap.file.application.FileScanEventBus",
    "com.awap.webhook.infrastructure.WebhookVerifier",
    "com.awap.engine.application.NodeTimeoutHandler",
  ]),
  "awap-common": Object.freeze([]),
});

const DATA_MARKER_PREFIX = "<!-- coverage-baseline-data: ";

const REQUIRED_CSV_COLUMNS = [
  "PACKAGE",
  "CLASS",
  "LINE_MISSED",
  "LINE_COVERED",
  "BRANCH_MISSED",
  "BRANCH_COVERED",
];

function toDotted(packageName) {
  return packageName.replaceAll("/", ".");
}

function isExcludedRow(packageName, className) {
  return isExcludedPackage(toDotted(packageName), className);
}

function counter(line) {
  return {
    lineCovered: line.lineCovered,
    lineMissed: line.lineMissed,
    branchCovered: line.branchCovered,
    branchMissed: line.branchMissed,
  };
}

function emptyAggregate() {
  return { lineCovered: 0, lineMissed: 0, branchCovered: 0, branchMissed: 0 };
}

function add(target, source) {
  target.lineCovered += source.lineCovered;
  target.lineMissed += source.lineMissed;
  target.branchCovered += source.branchCovered;
  target.branchMissed += source.branchMissed;
  return target;
}

function parseInteger(value, column, source, lineNumber) {
  if (!/^\d+$/.test(value ?? "")) {
    throw new Error(`${source}:${lineNumber}: ${column} is not an integer: "${value}"`);
  }
  return Number(value);
}

/**
 * Parses a JaCoCo CSV report. Packages whose names contain a comma are quoted
 * by JaCoCo; the supported report format never quotes class names, so a simple
 * split is enough and any deviation is reported instead of guessed.
 */
export function parseCsv(text, source = "jacoco.csv") {
  const lines = text.split(/\r?\n/).filter((line) => line.trim().length > 0);
  if (lines.length === 0) {
    throw new Error(`${source}: report is empty`);
  }
  const header = lines[0].split(",").map((column) => column.trim());
  const index = Object.fromEntries(header.map((name, position) => [name, position]));
  for (const column of REQUIRED_CSV_COLUMNS) {
    if (index[column] === undefined) {
      throw new Error(`${source}: missing the ${column} column`);
    }
  }
  const requiredWidth = Math.max(...REQUIRED_CSV_COLUMNS.map((column) => index[column])) + 1;
  const rows = [];
  for (let position = 1; position < lines.length; position++) {
    const columns = lines[position].split(",");
    if (columns.length < requiredWidth) {
      throw new Error(
        `${source}:${position + 1}: expected at least ${requiredWidth} columns, found ${columns.length}`,
      );
    }
    rows.push({
      packageName: columns[index.PACKAGE].trim(),
      className: columns[index.CLASS].trim(),
      lineCovered: parseInteger(columns[index.LINE_COVERED], "LINE_COVERED", source, position + 1),
      lineMissed: parseInteger(columns[index.LINE_MISSED], "LINE_MISSED", source, position + 1),
      branchCovered: parseInteger(
        columns[index.BRANCH_COVERED],
        "BRANCH_COVERED",
        source,
        position + 1,
      ),
      branchMissed: parseInteger(
        columns[index.BRANCH_MISSED],
        "BRANCH_MISSED",
        source,
        position + 1,
      ),
    });
  }
  return rows;
}

function attribute(attributes, name) {
  // The word boundary keeps `name` from matching the tail of `sourcefilename`.
  const match = attributes.match(new RegExp(`\\b${name}="([^"]*)"`));
  return match ? match[1] : null;
}

/**
 * Parses a JaCoCo XML report into the same row shape as the CSV parser. A class
 * without a BRANCH counter has no branches (0/0), which JaCoCo omits instead of
 * writing a zero counter.
 */
export function parseXml(text, source = "jacoco.xml") {
  if (!/<report[\s>]/.test(text)) {
    throw new Error(`${source}: not a JaCoCo XML report`);
  }
  const rows = [];
  const packagePattern = /<package\s+name="([^"]+)"\s*>([\s\S]*?)<\/package>/g;
  for (const packageMatch of text.matchAll(packagePattern)) {
    const packageName = packageMatch[1];
    const classPattern = /<class\s+([^>]*)>([\s\S]*?)<\/class>/g;
    for (const classMatch of packageMatch[2].matchAll(classPattern)) {
      const className = attribute(classMatch[1], "name");
      if (!className) {
        throw new Error(`${source}: a class in ${packageName} has no name`);
      }
      // JaCoCo writes one counter per method first and the class total last, so
      // the class-level LINE/BRANCH value is the last matching counter.
      const lineCounter = [...classMatch[2].matchAll(/<counter\s+type="LINE"\s+([^>]*)\/>/g)].at(-1);
      if (!lineCounter) {
        throw new Error(`${source}: class ${className} has no LINE counter`);
      }
      const branchCounter = [
        ...classMatch[2].matchAll(/<counter\s+type="BRANCH"\s+([^>]*)\/>/g),
      ].at(-1);
      rows.push({
        packageName: toDotted(packageName),
        className: className.split("/").at(-1),
        lineCovered: Number(attribute(lineCounter[1], "covered")),
        lineMissed: Number(attribute(lineCounter[1], "missed")),
        branchCovered: branchCounter ? Number(attribute(branchCounter[1], "covered")) : 0,
        branchMissed: branchCounter ? Number(attribute(branchCounter[1], "missed")) : 0,
      });
    }
  }
  return rows;
}

/**
 * Aggregates the non-excluded rows of one module and exposes the critical-class
 * values. An empty module is returned with zero totals instead of being skipped.
 */
export function aggregateModule(rows) {
  const totals = emptyAggregate();
  const classes = new Map();
  for (const row of rows) {
    if (isExcludedRow(row.packageName, row.className)) {
      continue;
    }
    add(totals, counter(row));
    const key = `${row.packageName}.${row.className}`;
    const existing = classes.get(key) ?? emptyAggregate();
    classes.set(key, add(existing, counter(row)));
  }
  return { totals, classes };
}

export function percentage(covered, missed) {
  const total = covered + missed;
  if (total === 0) {
    return "n/a";
  }
  return `${((covered / total) * 100).toFixed(2)}%`;
}

function coveredOverTotal(aggregate, prefix) {
  return `${aggregate[`${prefix}Covered`]} / ${aggregate[`${prefix}Covered`] + aggregate[`${prefix}Missed`]}`;
}

function moduleRow(moduleReport) {
  const { totals } = moduleReport;
  return `| \`${moduleReport.name}\` | ${coveredOverTotal(totals, "line")} | ${percentage(totals.lineCovered, totals.lineMissed)} | ${coveredOverTotal(totals, "branch")} | ${percentage(totals.branchCovered, totals.branchMissed)} |`;
}

function criticalRow(moduleReport, fqcn) {
  const aggregate = moduleReport.classes.get(fqcn);
  if (!aggregate) {
    throw new Error(`critical class ${fqcn} is missing from the ${moduleReport.name} report`);
  }
  return `| \`${fqcn}\` | \`${moduleReport.name}\` | ${coveredOverTotal(aggregate, "line")} | ${percentage(aggregate.lineCovered, aggregate.lineMissed)} | ${coveredOverTotal(aggregate, "branch")} | ${percentage(aggregate.branchCovered, aggregate.branchMissed)} |`;
}

function machineReadableData(modules) {
  const data = { modules: {}, classes: {} };
  for (const moduleReport of modules) {
    data.modules[moduleReport.name] = {
      line: percentage(moduleReport.totals.lineCovered, moduleReport.totals.lineMissed),
      lineCovered: moduleReport.totals.lineCovered,
      lineTotal: moduleReport.totals.lineCovered + moduleReport.totals.lineMissed,
      branch: percentage(moduleReport.totals.branchCovered, moduleReport.totals.branchMissed),
      branchCovered: moduleReport.totals.branchCovered,
      branchTotal: moduleReport.totals.branchCovered + moduleReport.totals.branchMissed,
    };
    for (const fqcn of CRITICAL_CLASSES[moduleReport.name] ?? []) {
      const aggregate = moduleReport.classes.get(fqcn);
      if (!aggregate) {
        throw new Error(`critical class ${fqcn} is missing from the ${moduleReport.name} report`);
      }
      data.classes[fqcn] = {
        module: moduleReport.name,
        line: percentage(aggregate.lineCovered, aggregate.lineMissed),
        branch: percentage(aggregate.branchCovered, aggregate.branchMissed),
      };
    }
  }
  return JSON.stringify(data);
}

/** Renders the deterministic baseline document. */
export function renderDocument(modules) {
  const lines = [
    "# Coverage Baseline",
    "",
    "<!-- Generated by scripts/coverage-baseline.mjs from the JaCoCo CSV and XML",
    "     reports. Regenerate it with `make coverage-baseline`; do not edit by hand. -->",
    "",
    "Every module aggregate applies the JaCoCo rule exclusions:",
    "`com.awap.**.config.*`, `com.awap.**.interfaces.dto.*` and",
    "`com.awap.identity.IdentityApplication`. A module without classes is shown",
    "with zero totals instead of being omitted.",
    "",
    "## Modules",
    "",
    "| Module | Line covered / total | Line % | Branch covered / total | Branch % |",
    "| --- | --- | --- | --- | --- |",
    ...modules.map(moduleRow),
    "",
    "## Critical classes",
    "",
    "| Class | Module | Line covered / total | Line % | Branch covered / total | Branch % |",
    "| --- | --- | --- | --- | --- | --- |",
  ];
  let criticalCount = 0;
  for (const moduleReport of modules) {
    for (const fqcn of CRITICAL_CLASSES[moduleReport.name] ?? []) {
      lines.push(criticalRow(moduleReport, fqcn));
      criticalCount++;
    }
  }
  if (criticalCount === 0) {
    lines.push("| _none defined for the provided modules_ | | | | | |");
  }
  lines.push(
    "",
    "The critical classes are the JaCoCo CLASS rules from `backend/pom.xml` plus the",
    "issue #196 additions documented in `docs/coverage-ratchet.md`.",
    "",
    `${DATA_MARKER_PREFIX}${machineReadableData(modules)} -->`,
    "",
  );
  return lines.join("\n");
}

/** Extracts the machine-readable baseline embedded in a committed document. */
export function parseCommittedDocument(text) {
  const markerIndex = text.indexOf(DATA_MARKER_PREFIX);
  if (markerIndex === -1) {
    return null;
  }
  const start = markerIndex + DATA_MARKER_PREFIX.length;
  const end = text.indexOf(" -->", start);
  if (end === -1) {
    throw new Error("committed document has an unterminated coverage-baseline-data marker");
  }
  try {
    return JSON.parse(text.slice(start, end));
  } catch (error) {
    throw new Error(`committed document has malformed coverage-baseline-data: ${error.message}`);
  }
}

function parsePercentage(value) {
  if (value === "n/a" || value === undefined) {
    return null;
  }
  return Number(value.replace("%", ""));
}

function deltaRow(label, previous, current) {
  const before = parsePercentage(previous);
  const after = parsePercentage(current);
  if (before === null || after === null) {
    return `| ${label} | ${previous ?? "n/a"} | ${current} | n/a |`;
  }
  const difference = after - before;
  return `| ${label} | ${before.toFixed(2)}% | ${after.toFixed(2)}% | ${difference >= 0 ? "+" : ""}${difference.toFixed(2)} pp |`;
}

/** Renders the PR-comment delta between the committed and the generated values. */
export function renderDelta(previous, modules) {
  const current = JSON.parse(machineReadableData(modules));
  const lines = ["## Coverage baseline delta", ""];
  if (!previous) {
    lines.push(
      "The committed document has no machine-readable baseline yet, so this is the",
      "generated measurement:",
      "",
      "| Metric | Committed | Generated | Delta |",
      "| --- | --- | --- | --- |",
    );
  } else {
    lines.push(
      "| Metric | Committed | Generated | Delta |",
      "| --- | --- | --- | --- |",
    );
  }
  for (const [moduleName, values] of Object.entries(current.modules)) {
    const old = previous?.modules?.[moduleName] ?? {};
    lines.push(deltaRow(`${moduleName} line`, old.line, values.line));
    lines.push(deltaRow(`${moduleName} branch`, old.branch, values.branch));
  }
  for (const [fqcn, values] of Object.entries(current.classes)) {
    const old = previous?.classes?.[fqcn] ?? {};
    lines.push(deltaRow(`${fqcn} line`, old.line, values.line));
    lines.push(deltaRow(`${fqcn} branch`, old.branch, values.branch));
  }
  lines.push("");
  return lines.join("\n");
}

/** Returns the drift result for the committed document. */
export function checkDrift(expected, actual) {
  if (actual === null) {
    return { passed: false, reason: "the committed baseline document is missing" };
  }
  if (expected === actual) {
    return { passed: true, reason: null };
  }
  return {
    passed: false,
    reason: "the committed baseline document differs from the generated one; run `make coverage-baseline`",
  };
}

function pathValue(spec, option) {
  const separator = spec.indexOf("=");
  if (separator <= 0 || separator === spec.length - 1) {
    throw new Error(`${option} expects <module>=<path>, got "${spec}"`);
  }
  return { name: spec.slice(0, separator), path: spec.slice(separator + 1) };
}

function nextValue(argv, index, option) {
  const value = argv[index];
  if (value === undefined || value.startsWith("--")) {
    throw new Error(`${option} requires a value`);
  }
  return value;
}

export function parseArgs(argv) {
  const options = {
    modules: [],
    xml: new Map(),
    document: "docs/coverage-baseline.md",
    check: false,
    emit: null,
    delta: null,
    summary: null,
  };
  const seenXml = new Set();
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--csv") {
      const { name, path } = pathValue(nextValue(argv, ++i, "--csv"), "--csv");
      if (options.modules.some((moduleReport) => moduleReport.name === name)) {
        throw new Error(`duplicate module: ${name}`);
      }
      options.modules.push({ name, path });
    } else if (arg === "--xml") {
      const { name, path } = pathValue(nextValue(argv, ++i, "--xml"), "--xml");
      if (seenXml.has(name)) {
        throw new Error(`duplicate XML report for module: ${name}`);
      }
      seenXml.add(name);
      options.xml.set(name, path);
    } else if (arg === "--document") {
      options.document = nextValue(argv, ++i, "--document");
    } else if (arg === "--check") {
      options.check = true;
    } else if (arg === "--emit") {
      options.emit = nextValue(argv, ++i, "--emit");
    } else if (arg === "--delta") {
      options.delta = nextValue(argv, ++i, "--delta");
    } else if (arg === "--summary") {
      options.summary = nextValue(argv, ++i, "--summary");
    } else if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else {
      throw new Error(`unknown argument: ${arg}`);
    }
  }
  if (!options.help && options.modules.length === 0) {
    throw new Error("at least one --csv <module>=<path> is required");
  }
  return options;
}

function readRequired(path, description) {
  try {
    return readFileSync(path, "utf8");
  } catch (error) {
    throw new Error(`cannot read the ${description} at ${path}: ${error.code ?? error.message}`);
  }
}

function writePath(path, content) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content);
}

const HELP = `Usage: node scripts/coverage-baseline.mjs --csv <module>=<jacoco.csv> [options]

Options:
  --csv <module>=<path>   JaCoCo CSV report of a module (repeatable)
  --xml <module>=<path>   JaCoCo XML report of the same module (repeatable)
  --document <path>       Committed baseline document (default docs/coverage-baseline.md)
  --check                 Fail when the committed document differs from the generated one
  --emit <path>           Write the generated document
  --delta <path>          Write the PR-comment delta table
  --summary <path>        Append the delta to a markdown summary file
`;

/**
 * Runs the generator. Returns 0 on success, 1 on a generation/drift failure and
 * 2 for a usage error, matching the repository's other gate scripts.
 */
export function main(argv = process.argv.slice(2), log = (line) => process.stdout.write(`${line}\n`)) {
  let options;
  try {
    options = parseArgs(argv);
  } catch (error) {
    log(`FAIL: ${error.message}`);
    log(HELP);
    return 2;
  }
  if (options.help) {
    log(HELP);
    return 0;
  }
  try {
    const modules = options.modules.map((module) => {
      const csvRows = parseCsv(readRequired(module.path, "JaCoCo CSV"), module.path);
      const xmlPath = options.xml.get(module.name);
      if (options.xml.size > 0) {
        if (!xmlPath) {
          throw new Error(`module ${module.name} has no --xml report`);
        }
        const xmlRows = parseXml(readRequired(xmlPath, "JaCoCo XML"), xmlPath);
        const csvAggregate = aggregateModule(csvRows).totals;
        const xmlAggregate = aggregateModule(xmlRows).totals;
        for (const field of ["lineCovered", "lineMissed", "branchCovered", "branchMissed"]) {
          if (csvAggregate[field] !== xmlAggregate[field]) {
            throw new Error(
              `module ${module.name}: CSV and XML disagree on ${field} (${csvAggregate[field]} vs ${xmlAggregate[field]})`,
            );
          }
        }
      }
      const { totals, classes } = aggregateModule(csvRows);
      return { name: module.name, totals, classes };
    });
    for (const name of options.xml.keys()) {
      if (!options.modules.some((module) => module.name === name)) {
        throw new Error(`--xml module ${name} has no matching --csv report`);
      }
    }
    const document = renderDocument(modules);
    if (options.emit) {
      writePath(options.emit, document);
    }
    let committed = null;
    if (options.check || options.delta) {
      let committedText = null;
      try {
        committedText = readFileSync(options.document, "utf8");
      } catch (error) {
        if (error.code !== "ENOENT") {
          throw new Error(`cannot read the committed document at ${options.document}: ${error.message}`);
        }
      }
      committed = committedText === null ? null : parseCommittedDocument(committedText);
      const result = checkDrift(document, committedText);
      const delta = renderDelta(committed, modules);
      if (options.delta) {
        writePath(options.delta, delta);
      }
      if (options.summary) {
        appendFileSync(options.summary, `${delta}\n`);
      }
      if (!result.passed) {
        log(`FAIL: ${result.reason}`);
        return 1;
      }
      log(`Coverage baseline matches ${options.document}.`);
    } else if (options.summary) {
      appendFileSync(options.summary, `${renderDelta(committed, modules)}\n`);
    }
    if (options.emit) {
      log(`Wrote the coverage baseline to ${options.emit}.`);
    }
    return 0;
  } catch (error) {
    log(`FAIL: ${error.message}`);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = main();
}
