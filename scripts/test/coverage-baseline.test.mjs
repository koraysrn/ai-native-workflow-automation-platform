import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  CRITICAL_CLASSES,
  aggregateModule,
  checkDrift,
  main,
  parseArgs,
  parseCommittedDocument,
  parseCsv,
  parseXml,
  percentage,
  renderDelta,
  renderDocument,
} from "../coverage-baseline.mjs";

const CSV_HEADER =
  "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED";

const CSV = [
  CSV_HEADER,
  "AWAP,com.awap.common.api,DemoService,0,10,1,3,0,10",
  "AWAP,com.awap.organization.interfaces.dto,PositionResponse,0,1,0,0,0,1",
  "AWAP,com.awap.engine.config,EngineConfig,5,0,0,0,5,0",
  "AWAP,com.awap.identity,IdentityApplication,3,0,0,0,3,0",
].join("\n");

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<report name="awap">
  <package name="com/awap/common/api">
    <class name="com/awap/common/api/DemoService" sourcefilename="DemoService.java">
      <method name="run" desc="()V">
        <counter type="LINE" missed="0" covered="4"/>
        <counter type="BRANCH" missed="1" covered="1"/>
      </method>
      <counter type="INSTRUCTION" missed="0" covered="10"/>
      <counter type="BRANCH" missed="1" covered="3"/>
      <counter type="LINE" missed="0" covered="10"/>
    </class>
  </package>
</report>`;

function emptyCsvPath(dir) {
  const path = join(dir, "empty.csv");
  writeFileSync(path, `${CSV_HEADER}\n`, "utf8");
  return path;
}

function writeReport(dir, name, content) {
  const path = join(dir, name);
  writeFileSync(path, content, "utf8");
  return path;
}

function identityRows() {
  return CRITICAL_CLASSES["awap-identity"].map((fqcn) => {
    const dot = fqcn.lastIndexOf(".");
    return {
      packageName: fqcn.slice(0, dot),
      className: fqcn.slice(dot + 1),
      lineCovered: 10,
      lineMissed: 0,
      branchCovered: 5,
      branchMissed: 0,
    };
  });
}

function identityCsv() {
  return [
    CSV_HEADER,
    ...identityRows().map(
      (row) =>
        `AWAP,${row.packageName},${row.className},0,10,0,5,${row.lineMissed},${row.lineCovered}`,
    ),
  ].join("\n");
}

function identityXml() {
  const packages = new Map();
  for (const row of identityRows()) {
    const entries = packages.get(row.packageName) ?? [];
    entries.push(row);
    packages.set(row.packageName, entries);
  }
  const blocks = [...packages.entries()].map(([packageName, rows]) => {
    const classes = rows
      .map(
        (row) => `    <class name="${packageName.replaceAll(".", "/")}/${row.className}">
      <counter type="LINE" missed="0" covered="10"/>
      <counter type="BRANCH" missed="0" covered="5"/>
    </class>`,
      )
      .join("\n");
    return `  <package name="${packageName.replaceAll(".", "/")}">
${classes}
  </package>`;
  });
  return `<report name="awap">\n${blocks.join("\n")}\n</report>`;
}

function runMain(args) {
  const output = [];
  const code = main(args, (line) => output.push(line));
  return { code, output: output.join("\n") };
}

test("parseCsv reads coverage rows and rejects malformed reports", () => {
  const rows = parseCsv(CSV, "unit.csv");
  assert.equal(rows.length, 4);
  assert.deepEqual(rows[0], {
    packageName: "com.awap.common.api",
    className: "DemoService",
    lineCovered: 10,
    lineMissed: 0,
    branchCovered: 3,
    branchMissed: 1,
  });
  assert.throws(() => parseCsv("", "empty.csv"), /report is empty/);
  assert.throws(
    () => parseCsv("GROUP,PACKAGE,CLASS\nAWAP,a,B,0,0,0,0,0,0", "missing.csv"),
    /missing the LINE_MISSED column/,
  );
  assert.throws(() => parseCsv(`${CSV_HEADER}\nAWAP,a,B,0,0`, "short.csv"), /expected at least 9 columns/);
  assert.throws(
    () => parseCsv(`${CSV_HEADER}\nAWAP,a,B,0,0,0,0,0,nope`, "bad.csv"),
    /LINE_COVERED is not an integer/,
  );
});

test("parseXml takes the class-level counters and rejects non-JaCoCo input", () => {
  const [row] = parseXml(XML, "unit.xml");
  assert.deepEqual(row, {
    packageName: "com.awap.common.api",
    className: "DemoService",
    lineCovered: 10,
    lineMissed: 0,
    branchCovered: 3,
    branchMissed: 1,
  });
  assert.throws(() => parseXml("<html></html>", "bad.xml"), /not a JaCoCo XML report/);
  assert.throws(
    () =>
      parseXml(
        '<report><package name="a"><class sourcefilename="X.java"><counter type="LINE" missed="0" covered="1"/></class></package></report>',
        "nameless.xml",
      ),
    /has no name/,
  );
  assert.throws(
    () =>
      parseXml(
        '<report><package name="a"><class name="a/B"><counter type="BRANCH" missed="0" covered="1"/></class></package></report>',
        "lines.xml",
      ),
    /has no LINE counter/,
  );
});

test("aggregateModule applies the bundle exclusions and keeps empty modules", () => {
  const composed = aggregateModule(parseCsv(CSV));
  assert.equal(composed.totals.lineCovered, 10);
  assert.equal(composed.totals.lineMissed, 0);
  assert.equal(composed.totals.branchCovered, 3);
  assert.equal(composed.totals.branchMissed, 1);
  assert.equal(composed.classes.has("com.awap.common.api.DemoService"), true);
  assert.equal(composed.classes.has("com.awap.organization.interfaces.dto.PositionResponse"), false);

  const empty = aggregateModule(parseCsv(`${CSV_HEADER}\n`));
  assert.deepEqual(empty.totals, {
    lineCovered: 0,
    lineMissed: 0,
    branchCovered: 0,
    branchMissed: 0,
  });
});

test("percentage renders two decimals and n/a for zero totals", () => {
  assert.equal(percentage(0, 0), "n/a");
  assert.equal(percentage(3, 1), "75.00%");
  assert.equal(percentage(1, 2), "33.33%");
});

test("renderDocument includes module and critical-class rows plus the data marker", () => {
  const document = renderDocument([
    { name: "awap-identity", ...aggregateModule(parseCsv(identityCsv())) },
    { name: "awap-common", ...aggregateModule(parseCsv(CSV)) },
  ]);
  assert.match(document, /\| `awap-identity` \| 170 \/ 170 \| 100\.00% \|/);
  assert.match(document, /\| `awap-common` \| 10 \/ 10 \| 100\.00% \|/);
  assert.match(document, /`com\.awap\.organization\.domain\.Position`/);
  assert.match(document, /coverage-baseline-data: /);
  const data = parseCommittedDocument(document);
  assert.equal(data.modules["awap-identity"].line, "100.00%");
  assert.equal(data.classes["com.awap.ai.application.AiBudgetGuard"].branch, "100.00%");
});

test("renderDocument reports a module without critical classes instead of skipping it", () => {
  const document = renderDocument([{ name: "awap-common", ...aggregateModule(parseCsv(CSV)) }]);
  assert.match(document, /_none defined for the provided modules_/);
});

test("renderDocument fails when a critical class is missing from the report", () => {
  assert.throws(
    () => renderDocument([{ name: "awap-identity", ...aggregateModule(parseCsv(CSV)) }]),
    /critical class com\.awap\.notification\.domain\.NotificationTemplate is missing/,
  );
});

test("parseCommittedDocument tolerates legacy documents and rejects broken markers", () => {
  assert.equal(parseCommittedDocument("no marker here"), null);
  assert.throws(
    () => parseCommittedDocument("<!-- coverage-baseline-data: {\"a\":1}"),
    /unterminated/,
  );
  assert.throws(
    () => parseCommittedDocument('<!-- coverage-baseline-data: {oops} -->'),
    /malformed coverage-baseline-data/,
  );
});

test("renderDelta shows the committed-to-generated change", () => {
  const modules = [{ name: "awap-common", ...aggregateModule(parseCsv(CSV)) }];
  const withoutBaseline = renderDelta(null, modules);
  assert.match(withoutBaseline, /no machine-readable baseline yet/);
  const delta = renderDelta(
    {
      modules: { "awap-common": { line: "90.00%", branch: "50.00%" } },
      classes: {},
    },
    modules,
  );
  assert.match(delta, /\| awap-common line \| 90\.00% \| 100\.00% \| \+10\.00 pp \|/);
  assert.match(delta, /\| awap-common branch \| 50\.00% \| 75\.00% \| \+25\.00 pp \|/);
  const unavailable = renderDelta(
    { modules: { "awap-common": { line: "n/a", branch: "n/a" } }, classes: {} },
    modules,
  );
  assert.match(unavailable, /\| awap-common line \| n\/a \| 100\.00% \| n\/a \|/);
});

test("checkDrift distinguishes a match, a stale document and a missing document", () => {
  assert.deepEqual(checkDrift("same", "same"), { passed: true, reason: null });
  assert.match(checkDrift("new", "old").reason, /differs from the generated one/);
  assert.match(checkDrift("new", null).reason, /is missing/);
});

test("parseArgs validates the module reports and the option values", () => {
  const options = parseArgs(["--csv", "awap-common=a.csv", "--xml", "awap-common=a.xml"]);
  assert.equal(options.modules.length, 1);
  assert.equal(options.xml.get("awap-common"), "a.xml");
  assert.equal(options.document, "docs/coverage-baseline.md");
  assert.throws(() => parseArgs(["--csv", "awap-common=a.csv", "--csv", "awap-common=b.csv"]), /duplicate module/);
  assert.throws(
    () => parseArgs(["--csv", "awap-common=a.csv", "--xml", "awap-common=a.xml", "--xml", "awap-common=b.xml"]),
    /duplicate XML report/,
  );
  assert.throws(() => parseArgs(["--csv", "no-extension"]), /expects <module>=<path>/);
  assert.throws(() => parseArgs(["--csv"]), /--csv requires a value/);
  assert.throws(() => parseArgs(["--document"]), /--document requires a value/);
  assert.throws(() => parseArgs(["--nope"]), /unknown argument/);
  assert.throws(() => parseArgs([]), /at least one --csv/);
  assert.equal(parseArgs(["--help"]).help, true);
});

test("main emits the document, verifies it and writes the delta", () => {
  const dir = mkdtempSync(join(tmpdir(), "awap-baseline-"));
  try {
    const csv = writeReport(dir, "jacoco.csv", CSV);
    const xml = writeReport(dir, "jacoco.xml", XML);
    const generatedPath = join(dir, "generated.md");
    const documentPath = join(dir, "coverage-baseline.md");
    const deltaPath = join(dir, "delta.md");
    const summaryPath = join(dir, "summary.md");
    writeFileSync(summaryPath, "", "utf8");

    const first = runMain([
      "--csv",
      `awap-common=${csv}`,
      "--xml",
      `awap-common=${xml}`,
      "--document",
      documentPath,
      "--check",
      "--emit",
      generatedPath,
      "--delta",
      deltaPath,
      "--summary",
      summaryPath,
    ]);
    assert.equal(first.code, 1);
    assert.match(first.output, /committed baseline document is missing/);
    assert.equal(existsSync(generatedPath), true);
    writeFileSync(documentPath, readFileSync(generatedPath, "utf8"), "utf8");

    const second = runMain([
      "--csv",
      `awap-common=${csv}`,
      "--xml",
      `awap-common=${xml}`,
      "--document",
      documentPath,
      "--check",
      "--delta",
      deltaPath,
      "--summary",
      summaryPath,
    ]);
    assert.equal(second.code, 0);
    assert.match(second.output, /matches/);
    assert.match(readFileSync(deltaPath, "utf8"), /awap-common line/);
    assert.match(readFileSync(summaryPath, "utf8"), /Coverage baseline delta/);

    writeFileSync(
      documentPath,
      readFileSync(documentPath, "utf8").replace("100.00%", "99.00%"),
      "utf8",
    );
    const drift = runMain([
      "--csv",
      `awap-common=${csv}`,
      "--document",
      documentPath,
      "--check",
    ]);
    assert.equal(drift.code, 1);
    assert.match(drift.output, /differs from the generated one/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("main reports an empty module, missing reports and CSV/XML disagreement", () => {
  const dir = mkdtempSync(join(tmpdir(), "awap-baseline-bad-"));
  try {
    const empty = emptyCsvPath(dir);
    const emitted = join(dir, "empty.md");
    const zero = runMain(["--csv", `awap-common=${empty}`, "--emit", emitted]);
    assert.equal(zero.code, 0);
    const document = readFileSync(emitted, "utf8");
    assert.match(document, /\| `awap-common` \| 0 \/ 0 \| n\/a \| 0 \/ 0 \| n\/a \|/);

    const missing = runMain(["--csv", `awap-common=${join(dir, "nope.csv")}`]);
    assert.equal(missing.code, 1);
    assert.match(missing.output, /cannot read the JaCoCo CSV/);

    const csv = writeReport(dir, "good.csv", CSV);
    const wrongXml = writeReport(
      dir,
      "wrong.xml",
      '<report><package name="com/awap/common/api"><class name="DemoService"><counter type="LINE" missed="0" covered="1"/></class></package></report>',
    );
    const disagreement = runMain(["--csv", `awap-common=${csv}`, "--xml", `awap-common=${wrongXml}`]);
    assert.equal(disagreement.code, 1);
    assert.match(disagreement.output, /CSV and XML disagree on lineCovered/);

    const missingXml = runMain(["--csv", `awap-common=${csv}`, "--xml", "other=whatever.xml"]);
    assert.equal(missingXml.code, 1);
    assert.match(missingXml.output, /module awap-common has no --xml report/);

    const goodXml = writeReport(dir, "good.xml", XML);
    const unknownXml = runMain([
      "--csv",
      `awap-common=${csv}`,
      "--xml",
      `awap-common=${goodXml}`,
      "--xml",
      "unknown=whatever.xml",
    ]);
    assert.equal(unknownXml.code, 1);
    assert.match(unknownXml.output, /has no matching --csv report/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("main treats usage problems as exit code 2 and prints help", () => {
  const help = runMain(["--help"]);
  assert.equal(help.code, 0);
  assert.match(help.output, /Usage: node scripts\/coverage-baseline\.mjs/);
  const noArgs = runMain([]);
  assert.equal(noArgs.code, 2);
  assert.match(noArgs.output, /at least one --csv/);
  const unknown = runMain(["--bogus"]);
  assert.equal(unknown.code, 2);
  assert.match(unknown.output, /unknown argument/);
});
