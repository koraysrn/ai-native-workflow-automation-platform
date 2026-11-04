#!/usr/bin/env node
/**
 * Trivy regression signal for CI (issue #162).
 *
 * Trivy writes a JSON report; this script turns it into:
 *   1. a `$GITHUB_STEP_SUMMARY` block with the CVE severity breakdown,
 *   2. one `::error` annotation per gated (CRITICAL/HIGH) finding,
 *   3. a non-zero exit when the gate must block the merge.
 *
 * The decision to use a CI-only signal instead of a Grafana/Prometheus dashboard is recorded in
 * docs/adr/0005-jackson-bom-override.md. The runbook procedure is O21 in
 * docs/operations-runbook.md.
 */
import { appendFile, readFile, writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

export const SEVERITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"];
export const DEFAULT_FAIL_ON = ["CRITICAL", "HIGH"];

const JACKSON_HINT = /jackson/i;
const SEVERITY_RANK = new Map(SEVERITIES.map((severity, index) => [severity, index]));

export function normalizeSeverity(value) {
  const severity = String(value ?? "UNKNOWN").toUpperCase();
  return SEVERITIES.includes(severity) ? severity : "UNKNOWN";
}

export function parseSeverityList(value = DEFAULT_FAIL_ON.join(",")) {
  return String(value)
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean)
    .map(normalizeSeverity)
    .filter((severity, index, all) => all.indexOf(severity) === index);
}

/**
 * Flattens Trivy's `Results[].Vulnerabilities[]` shape into a stable finding list. Reports that
 * contain only secrets/misconfigurations (or no Results at all) yield an empty list.
 */
export function findingsFromReport(report) {
  const findings = [];
  const results = Array.isArray(report?.Results) ? report.Results : [];
  for (const result of results) {
    const target =
      typeof result?.Target === "string" && result.Target.length > 0 ? result.Target : "unknown";
    const vulnerabilities = Array.isArray(result?.Vulnerabilities) ? result.Vulnerabilities : [];
    for (const vulnerability of vulnerabilities) {
      const pkg = String(vulnerability?.PkgName ?? "unknown");
      const id = String(vulnerability?.VulnerabilityID ?? "UNKNOWN");
      const title = String(vulnerability?.Title ?? "");
      const fixedVersion = String(vulnerability?.FixedVersion ?? "");
      findings.push({
        target,
        id,
        severity: normalizeSeverity(vulnerability?.Severity),
        package: pkg,
        installedVersion: String(vulnerability?.InstalledVersion ?? ""),
        fixedVersion,
        title,
        url: String(vulnerability?.PrimaryURL ?? ""),
        fixable: fixedVersion.length > 0,
        jackson: JACKSON_HINT.test(`${pkg} ${id} ${title}`),
      });
    }
  }
  return findings;
}

export function severityBreakdown(findings) {
  const breakdown = {};
  for (const severity of SEVERITIES) {
    breakdown[severity] = { total: 0, fixable: 0 };
  }
  for (const finding of findings) {
    const bucket = breakdown[finding.severity] ?? breakdown.UNKNOWN;
    bucket.total += 1;
    if (finding.fixable) {
      bucket.fixable += 1;
    }
  }
  return breakdown;
}

export function gatedFindings(findings, failOn = DEFAULT_FAIL_ON) {
  const gated = new Set(failOn);
  return findings
    .filter((finding) => gated.has(finding.severity))
    .sort(
      (left, right) =>
        SEVERITY_RANK.get(left.severity) - SEVERITY_RANK.get(right.severity) ||
        left.id.localeCompare(right.id) ||
        left.package.localeCompare(right.package),
    );
}

function markdownCell(value) {
  const text = String(value ?? "").trim();
  return text.length > 0 ? `\`${text.replaceAll("|", "\\|").replaceAll("`", "'")}\`` : "—";
}

function summaryTable(breakdown) {
  const lines = ["| Severity | Findings | Fixable |", "|---|---:|---:|"];
  for (const severity of SEVERITIES) {
    const entry = breakdown[severity];
    lines.push(`| ${severity} | ${entry.total} | ${entry.fixable} |`);
  }
  return lines;
}

function findingsTable(findings, maxRows) {
  const visible = findings.slice(0, maxRows);
  const lines = [
    "| Severity | CVE | Package | Installed | Fixed | Target |",
    "|---|---|---|---|---|---|",
  ];
  for (const finding of visible) {
    lines.push(
      [
        finding.severity,
        markdownCell(finding.id),
        markdownCell(finding.package),
        markdownCell(finding.installedVersion),
        markdownCell(finding.fixedVersion),
        markdownCell(finding.target),
      ].join(" | ").replace(/^/, "| ").replace(/$/, " |"),
    );
  }
  if (findings.length > visible.length) {
    lines.push("");
    lines.push(
      `_${findings.length - visible.length} more finding(s) are in the uploaded ` +
        "`trivy-results.json` artifact._",
    );
  }
  return lines;
}

/**
 * Renders the human-readable summary that GitHub shows on the run page and in the PR checks tab.
 */
export function renderSummary(findings, options = {}) {
  const failOn = options.failOn ?? DEFAULT_FAIL_ON;
  const maxFindings = options.maxFindings ?? 25;
  const gated = gatedFindings(findings, failOn);
  const breakdown = severityBreakdown(findings);
  const fixable = findings.filter((finding) => finding.fixable).length;
  const passed = gated.length === 0;

  const lines = [
    "## Trivy vulnerability gate",
    "",
    passed
      ? "**Result:** ✅ PASS — no gated findings."
      : `**Result:** ❌ FAIL — ${gated.length} gated finding(s).`,
    `**Gate:** fails on ${failOn.join(", ")}.`,
    `**Scan findings:** ${findings.length} (${fixable} fixable).`,
    "",
    ...summaryTable(breakdown),
    "",
  ];

  if (gated.length > 0) {
    lines.push("### Findings that fail the gate", "", ...findingsTable(gated, maxFindings), "");
  } else {
    lines.push("No findings match the gate severities.", "");
  }

  const jacksonFindings = gated.filter((finding) => finding.jackson);
  lines.push("### Jackson BOM override floor", "");
  if (jacksonFindings.length > 0) {
    lines.push(
      "A gated Jackson finding means the override floor in ADR-0005 needs attention. Run",
      "`backend/scripts/check-jackson-versions.sh` and confirm the resolved versions are at least",
      "2.21.7 (Jackson 2) and 3.1.7 (Jackson 3). Do **not** remove the override until Spring Boot's",
      "own BOM manages those versions.",
      "",
      ...findingsTable(jacksonFindings, 10),
      "",
    );
  } else {
    lines.push(
      "No gated Jackson findings in this scan. The floor check",
      "(`backend/scripts/check-jackson-versions.sh`) remains the primary guard described in ADR-0005.",
      "",
    );
  }

  lines.push(
    "### Next action",
    "",
    "1. Open the `trivy-results.json` artifact and the failing package in the Run log.",
    "2. Follow `docs/operations-runbook.md` → O21 — Trivy regression signal (issue #162).",
    "3. Fix the dependency or add a time-bounded, owned `.trivyignore` entry; never disable the gate.",
    "",
  );
  return lines.join("\n");
}

export function escapeWorkflowData(value) {
  return String(value ?? "")
    .replaceAll("%", "%25")
    .replaceAll("\r", "%0D")
    .replaceAll("\n", "%0A");
}

export function escapeWorkflowProperty(value) {
  return escapeWorkflowData(value).replaceAll(":", "%3A").replaceAll(",", "%2C");
}

/**
 * Emits one GitHub Actions annotation per gated finding so the PR Files/Checks view points at the
 * vulnerable manifest/lockfile instead of only showing a red job.
 */
export function renderAnnotations(findings, options = {}) {
  const max = options.max ?? 20;
  return findings.slice(0, max).map((finding) => {
    const properties = [];
    if (finding.target && finding.target !== "unknown") {
      properties.push(`file=${escapeWorkflowProperty(finding.target)}`, "line=1");
    }
    properties.push(
      `title=${escapeWorkflowProperty(`${finding.severity} ${finding.id} in ${finding.package}`)}`,
    );
    const message = escapeWorkflowData(
      [
        `${finding.package} ${finding.installedVersion || "?"} → ${finding.fixedVersion || "no fix"}`,
        finding.title,
        finding.jackson
          ? "Jackson finding: verify the BOM override floor in ADR-0005 before changing it."
          : "",
        finding.url,
      ]
        .filter(Boolean)
        .join(" | "),
    );
    return `::error ${properties.join(",")}::${message}`;
  });
}

function parseArgs(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (!argument.startsWith("--")) {
      continue;
    }
    const withoutPrefix = argument.slice(2);
    const equals = withoutPrefix.indexOf("=");
    if (equals >= 0) {
      args[withoutPrefix.slice(0, equals)] = withoutPrefix.slice(equals + 1);
      continue;
    }
    const next = argv[index + 1];
    if (next !== undefined && !next.startsWith("--")) {
      args[withoutPrefix] = next;
      index += 1;
    } else {
      args[withoutPrefix] = "true";
    }
  }
  return args;
}

/**
 * CLI entry point. Returns 0 when the gate passes, 1 when it fails and 2 when the report is
 * unreadable (the scan step must run first).
 */
export async function run(options = {}) {
  const argv = options.argv ?? process.argv.slice(2);
  const env = options.env ?? process.env;
  const stdout = options.stdout ?? console;
  const args = parseArgs(argv);
  const input = args.input ?? "trivy-results.json";
  const failOn = parseSeverityList(args["fail-on"] ?? DEFAULT_FAIL_ON.join(","));

  let report;
  try {
    report = JSON.parse(await readFile(input, "utf8"));
  } catch (error) {
    console.error(`Unable to read the Trivy JSON report at ${input}: ${error.message}`);
    console.error("The Trivy scan step must produce trivy-results.json before this report runs.");
    return 2;
  }

  const findings = findingsFromReport(report);
  const gated = gatedFindings(findings, failOn);
  const summary = renderSummary(findings, { failOn });
  stdout.log(summary);

  const annotations = renderAnnotations(gated);
  for (const annotation of annotations) {
    stdout.log(annotation);
  }
  if (annotations.length < gated.length) {
    stdout.log(
      `::warning title=Trivy annotations truncated::` +
        `${annotations.length} of ${gated.length} gating findings were annotated; ` +
        "see the uploaded trivy-results.json artifact.",
    );
  }

  const summaryTarget =
    args.summary === undefined ? env.GITHUB_STEP_SUMMARY : args.summary;
  if (summaryTarget && summaryTarget !== "false") {
    await appendFile(summaryTarget, `${summary}\n`);
  }

  if (args["json-out"]) {
    await writeFile(
      args["json-out"],
      `${JSON.stringify(
        {
          generatedAt: new Date().toISOString(),
          failOn,
          total: findings.length,
          gated: gated.length,
          breakdown: severityBreakdown(findings),
          findings,
        },
        null,
        2,
      )}\n`,
    );
  }

  if (gated.length > 0) {
    console.error(`Trivy gate failed: ${gated.length} ${failOn.join("/")} finding(s).`);
    return 1;
  }
  console.log("Trivy gate passed: no gated findings.");
  return 0;
}

const invokedDirectly =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (invokedDirectly) {
  run()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error) => {
      console.error(error?.stack ?? String(error));
      process.exitCode = 2;
    });
}
