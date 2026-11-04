import assert from "node:assert/strict";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import {
  findingsFromReport,
  gatedFindings,
  normalizeSeverity,
  parseSeverityList,
  renderAnnotations,
  renderSummary,
  run,
  severityBreakdown,
} from "../trivy-regression-report.mjs";

function reportWith(findings) {
  return {
    SchemaVersion: 2,
    ArtifactName: ".",
    ArtifactType: "filesystem",
    Results: [
      {
        Target: "backend/pom.xml",
        Class: "lang-pkgs",
        Type: "pom",
        Vulnerabilities: findings,
      },
      { Target: "ai-layer/uv.lock", Class: "lang-pkgs", Type: "uv-lock" },
    ],
  };
}

const jacksonHigh = {
  VulnerabilityID: "CVE-2026-89407",
  PkgName: "com.fasterxml.jackson.core:jackson-databind",
  InstalledVersion: "2.20.0",
  FixedVersion: "2.21.7",
  Severity: "HIGH",
  Title: "Jackson databind unsafe deserialization",
  PrimaryURL: "https://nvd.nist.gov/vuln/detail/CVE-2026-89407",
};

const criticalUnfixed = {
  VulnerabilityID: "CVE-2026-0002",
  PkgName: "left-pad",
  InstalledVersion: "0.1.0",
  FixedVersion: "",
  Severity: "CRITICAL",
  Title: "Remote code execution\n::error::injected",
  PrimaryURL: "https://example.test/CVE-2026-0002",
};

const mediumFixed = {
  VulnerabilityID: "CVE-2026-0001",
  PkgName: "org.example:lib",
  InstalledVersion: "1.0.0",
  FixedVersion: "1.0.1",
  Severity: "MEDIUM",
  Title: "Moderate issue",
};

test("flattens Trivy Results and ignores result blocks without vulnerabilities", () => {
  const findings = findingsFromReport(reportWith([jacksonHigh, mediumFixed]));
  assert.equal(findings.length, 2);
  assert.deepEqual(
    findings.map((finding) => [finding.id, finding.severity, finding.fixable, finding.jackson]),
    [
      ["CVE-2026-89407", "HIGH", true, true],
      ["CVE-2026-0001", "MEDIUM", true, false],
    ],
  );
});

test("severity breakdown counts every finding and the fixable subset", () => {
  const findings = findingsFromReport(reportWith([jacksonHigh, mediumFixed, criticalUnfixed]));
  const breakdown = severityBreakdown(findings);
  assert.deepEqual(breakdown.CRITICAL, { total: 1, fixable: 0 });
  assert.deepEqual(breakdown.HIGH, { total: 1, fixable: 1 });
  assert.deepEqual(breakdown.MEDIUM, { total: 1, fixable: 1 });
  assert.deepEqual(breakdown.LOW, { total: 0, fixable: 0 });
});

test("the gate selects CRITICAL/HIGH and orders them by severity then CVE", () => {
  const findings = findingsFromReport(reportWith([jacksonHigh, mediumFixed, criticalUnfixed]));
  assert.deepEqual(
    gatedFindings(findings).map((finding) => finding.id),
    ["CVE-2026-0002", "CVE-2026-89407"],
  );
  assert.deepEqual(
    gatedFindings(findings, ["MEDIUM"]).map((finding) => finding.id),
    ["CVE-2026-0001"],
  );
});

test("the summary reports FAIL, the severity breakdown and the Jackson floor", () => {
  const summary = renderSummary(findingsFromReport(reportWith([jacksonHigh, criticalUnfixed])));
  assert.match(summary, /\*\*Result:\*\* ❌ FAIL/);
  assert.match(summary, /\| CRITICAL \| 1 \| 0 \|/);
  assert.match(summary, /\| HIGH \| 1 \| 1 \|/);
  assert.match(summary, /CVE-2026-89407/);
  assert.match(summary, /backend\/scripts\/check-jackson-versions\.sh/);
  assert.match(summary, /2\.21\.7 \(Jackson 2\) and 3\.1\.7 \(Jackson 3\)/);
});

test("the summary passes and explains the floor when there are no gated findings", () => {
  const summary = renderSummary(findingsFromReport(reportWith([mediumFixed])));
  assert.match(summary, /\*\*Result:\*\* ✅ PASS/);
  assert.match(summary, /No findings match the gate severities/);
  assert.match(summary, /No gated Jackson findings/);
});

test("annotations point at the manifest and escape workflow-command input", () => {
  const gated = gatedFindings(
    findingsFromReport(reportWith([jacksonHigh, criticalUnfixed])),
  );
  const annotations = renderAnnotations(gated);
  assert.equal(annotations.length, 2);
  assert.match(annotations[0], /^::error file=backend\/pom\.xml,line=1,/);
  assert.match(annotations[0], /title=CRITICAL CVE-2026-0002 in left-pad/);
  assert.match(annotations[0], /%0A::error::injected/);
  assert.match(annotations[1], /Jackson finding: verify the BOM override floor/);
});

test("unknown severities are normalised instead of being dropped", () => {
  const findings = findingsFromReport(
    reportWith([{ VulnerabilityID: "CVE-X", PkgName: "pkg", Severity: "not-a-severity" }]),
  );
  assert.equal(normalizeSeverity("not-a-severity"), "UNKNOWN");
  assert.equal(findings[0].severity, "UNKNOWN");
  assert.deepEqual(parseSeverityList("high, CRITICAL,high"), ["HIGH", "CRITICAL"]);
});

test("the CLI fails closed on a malformed report shape", async () => {
  const directory = await mkdtemp(join(tmpdir(), "awap-trivy-invalid-"));
  const nullReport = join(directory, "null.json");
  const badResults = join(directory, "bad-results.json");
  await writeFile(nullReport, "null");
  await writeFile(badResults, JSON.stringify({ Results: "not-an-array" }));
  const sink = { log() {} };

  assert.equal(
    await run({ argv: ["--input", nullReport, "--summary", "false"], env: {}, stdout: sink }),
    2,
  );
  assert.equal(
    await run({ argv: ["--input", badResults, "--summary", "false"], env: {}, stdout: sink }),
    2,
  );
});

test("the CLI returns 1 for a gated report, 0 for a clean report and 2 when the file is missing", async () => {
  const directory = await mkdtemp(join(tmpdir(), "awap-trivy-report-"));
  const failing = join(directory, "failing.json");
  const clean = join(directory, "clean.json");
  await writeFile(failing, JSON.stringify(reportWith([jacksonHigh])));
  await writeFile(clean, JSON.stringify(reportWith([mediumFixed])));
  const sink = { log() {} };

  assert.equal(
    await run({
      argv: ["--input", failing, "--summary", "false"],
      env: {},
      stdout: sink,
    }),
    1,
  );
  assert.equal(
    await run({
      argv: ["--input", clean, "--summary", "false"],
      env: {},
      stdout: sink,
    }),
    0,
  );
  assert.equal(
    await run({
      argv: ["--input", join(directory, "missing.json"), "--summary", "false"],
      env: {},
      stdout: sink,
    }),
    2,
  );
});
