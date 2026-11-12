#!/usr/bin/env node
// Issue #196: enforce coverage on changed executable Java lines.
//
// The script intersects the added lines of a git diff with the JaCoCo XML report
// and fails when the changed executable lines are not covered to the requested
// threshold (default 100%). Generated code, DTOs and config classes are excluded
// explicitly so the gate stays meaningful instead of noisy.

import { execFileSync } from "node:child_process";
import { readFileSync, appendFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const EXCLUSIONS = [
  /\/interfaces\/dto\//,
  /\/config\//,
  /\/generated-sources\//,
  /MapperImpl\.java$/,
  /IdentityApplication\.java$/,
];

export function isExcluded(path) {
  return EXCLUSIONS.some((pattern) => pattern.test(path.replaceAll("\\", "/")));
}

export function isRelevantJavaPath(path) {
  const normalized = path.replaceAll("\\", "/");
  return (
    normalized.endsWith(".java") &&
    normalized.includes("/src/main/java/") &&
    !isExcluded(normalized)
  );
}

/**
 * Parses a unified diff and returns a map of added line numbers per relevant Java
 * source file. Deleted files and pure deletions produce no entries.
 */
export function parseDiff(diffText) {
  const changed = new Map();
  let current = null;
  for (const rawLine of diffText.split("\n")) {
    const line = rawLine.replace(/\r$/, "");
    if (line.startsWith("+++ b/")) {
      current = line.slice("+++ b/".length);
      if (!isRelevantJavaPath(current)) {
        current = null;
      } else if (!changed.has(current)) {
        changed.set(current, new Set());
      }
      continue;
    }
    if (line.startsWith("+++ ") || line.startsWith("--- ")) {
      current = null;
      continue;
    }
    if (!current) {
      continue;
    }
    const hunk = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/.exec(line);
    if (!hunk) {
      continue;
    }
    const start = Number(hunk[1]);
    const count = hunk[2] === undefined ? 1 : Number(hunk[2]);
    for (let offset = 0; offset < count; offset++) {
      changed.get(current).add(start + offset);
    }
  }
  return changed;
}

function parseAttributes(fragment) {
  const attributes = {};
  for (const match of fragment.matchAll(/([A-Za-z][\w-]*)="([^"]*)"/g)) {
    attributes[match[1]] = match[2];
  }
  return attributes;
}

/**
 * Parses one or more JaCoCo XML reports into a map keyed by the package-relative
 * source path (for example `com/awap/common/api/ErrorCode.java`). The value maps
 * executable line numbers to their covered flag.
 *
 * A declaration-only source file (a pure interface, an annotation, a constants
 * holder) is emitted by JaCoCo as `<sourcefile name="X.java"/>` because it has no
 * executable lines. Such a file is recorded with an empty map: its changed lines
 * are declarations, so they are neither covered nor uncovered. A file that is
 * absent from the report entirely still counts as uncovered, because that means
 * it was never executed by the test suite (see `evaluateChangedLines`).
 */
export function parseJacocoXml(xmlText, target = new Map()) {
  for (const packageMatch of xmlText.matchAll(/<package name="([^"]+)">([\s\S]*?)<\/package>/g)) {
    const packageName = packageMatch[1];
    const packageBody = packageMatch[2];
    for (const sourceMatch of packageBody.matchAll(
      /<sourcefile name="([^"]+)"\s*(?:\/>|>([\s\S]*?)<\/sourcefile>)/g,
    )) {
      const key = `${packageName}/${sourceMatch[1]}`;
      let lines = target.get(key);
      if (!lines) {
        lines = new Map();
        target.set(key, lines);
      }
      for (const lineMatch of (sourceMatch[2] ?? "").matchAll(/<line\s+([^>]*?)\/?>/g)) {
        const attributes = parseAttributes(lineMatch[1]);
        const nr = Number(attributes.nr);
        const missed = Number(attributes.mi ?? 0);
        const covered = Number(attributes.ci ?? 0);
        if (!Number.isFinite(nr) || missed + covered === 0) {
          continue;
        }
        lines.set(nr, covered > 0);
      }
    }
  }
  return target;
}

function lookupCoverage(path, coverage) {
  const normalized = path.replaceAll("\\", "/");
  for (const [key, lines] of coverage) {
    if (normalized.endsWith(key)) {
      return lines;
    }
  }
  return null;
}

/**
 * Computes changed executable line coverage. A changed Java file that has no
 * JaCoCo entry at all is treated as uncovered, because it means the code was
 * never executed by the test suite.
 */
export function evaluateChangedLines(changed, coverage, threshold = 100) {
  const files = [];
  let executable = 0;
  let covered = 0;
  for (const [path, lineNumbers] of changed) {
    if (!isRelevantJavaPath(path) || lineNumbers.size === 0) {
      continue;
    }
    const coverageLines = lookupCoverage(path, coverage);
    const uncovered = [];
    let fileExecutable = 0;
    let fileCovered = 0;
    for (const lineNumber of [...lineNumbers].sort((a, b) => a - b)) {
      if (!coverageLines) {
        fileExecutable++;
        uncovered.push(lineNumber);
        continue;
      }
      const coveredFlag = coverageLines.get(lineNumber);
      if (coveredFlag === undefined) {
        // Not an executable line (comment, blank, annotation-only, etc.).
        continue;
      }
      fileExecutable++;
      if (coveredFlag) {
        fileCovered++;
      } else {
        uncovered.push(lineNumber);
      }
    }
    if (fileExecutable === 0) {
      continue;
    }
    files.push({
      path,
      executable: fileExecutable,
      covered: fileCovered,
      uncovered,
    });
    executable += fileExecutable;
    covered += fileCovered;
  }
  const percentage = executable === 0 ? 100 : (covered / executable) * 100;
  return {
    files,
    executable,
    covered,
    percentage,
    threshold,
    passed: executable === 0 || percentage + 1e-9 >= threshold,
  };
}

function parseArgs(argv) {
  const options = { xml: [], base: "origin/main", threshold: 100, summary: null };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--xml") {
      options.xml.push(argv[++i]);
    } else if (arg === "--base") {
      options.base = argv[++i];
    } else if (arg === "--threshold") {
      options.threshold = Number(argv[++i]);
    } else if (arg === "--summary") {
      options.summary = argv[++i];
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  if (options.xml.length === 0) {
    throw new Error("At least one --xml JaCoCo report is required");
  }
  if (!Number.isFinite(options.threshold) || options.threshold < 0 || options.threshold > 100) {
    throw new Error("--threshold must be between 0 and 100");
  }
  return options;
}

export function formatSummary(result) {
  const lines = [
    "### Changed-line coverage",
    "",
    `- Changed executable lines: ${result.executable}`,
    `- Covered: ${result.covered}`,
    `- Coverage: ${result.percentage.toFixed(2)}%`,
    `- Minimum: ${result.threshold.toFixed(2)}%`,
    `- Result: ${result.passed ? "pass" : "fail"}`,
  ];
  if (result.files.length > 0) {
    lines.push("", "| File | Covered / Executable | Uncovered lines |", "| --- | --- | --- |");
    for (const file of result.files) {
      lines.push(
        `| ${file.path} | ${file.covered} / ${file.executable} | ${
          file.uncovered.length === 0 ? "-" : file.uncovered.join(", ")
        } |`,
      );
    }
  }
  return `${lines.join("\n")}\n`;
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const diff = execFileSync(
    "git",
    ["diff", "--unified=0", `${options.base}...HEAD`, "--", "backend"],
    { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 },
  );
  const changed = parseDiff(diff);
  const coverage = new Map();
  for (const xmlPath of options.xml) {
    parseJacocoXml(readFileSync(xmlPath, "utf8"), coverage);
  }
  const result = evaluateChangedLines(changed, coverage, options.threshold);
  const summary = formatSummary(result);
  process.stdout.write(summary);
  if (options.summary) {
    appendFileSync(options.summary, summary);
  }
  if (!result.passed) {
    process.stderr.write(
      `Changed-line coverage ${result.percentage.toFixed(2)}% is below the required ${options.threshold}%.\n`,
    );
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
