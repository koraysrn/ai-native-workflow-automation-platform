import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { main, validateJacocoReport } from "../check-jacoco-report.mjs";

const VALID = `<?xml version="1.0" encoding="UTF-8"?>
<report name="awap">
  <package name="com/awap/identity">
    <class name="com/awap/identity/domain/Tenant" />
  </package>
</report>`;

test("valid JaCoCo XML passes", () => {
  assert.equal(validateJacocoReport(VALID), undefined);
});

test("empty report is rejected", () => {
  assert.match(validateJacocoReport("", "empty.xml"), /empty or unreadable/);
});

test("non-JaCoCo XML is rejected", () => {
  assert.match(validateJacocoReport("<html></html>", "bad.xml"), /not a JaCoCo XML report/);
});

test("report without coverage entries is rejected", () => {
  assert.match(validateJacocoReport("<report name=\"x\"></report>", "none.xml"), /no coverage entries/);
});

test("main fails for a missing file and passes for a real report", () => {
  const dir = mkdtempSync(join(tmpdir(), "awap-jacoco-"));
  const valid = join(dir, "jacoco.xml");
  writeFileSync(valid, VALID, "utf8");
  try {
    const lines = [];
    assert.equal(main([join(dir, "missing.xml")], (line) => lines.push(line)), 1);
    assert.ok(lines.some((line) => line.startsWith("FAIL:")));
    assert.equal(main([valid], () => {}), 0);
    assert.equal(main([], () => {}), 2);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
