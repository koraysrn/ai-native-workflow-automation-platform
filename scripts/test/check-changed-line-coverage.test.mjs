import assert from "node:assert/strict";
import test from "node:test";

import {
  evaluateChangedLines,
  isExcluded,
  parseDiff,
  parseJacocoXml,
} from "../check-changed-line-coverage.mjs";

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<report name="awap">
  <package name="com/awap/demo">
    <sourcefile name="Demo.java">
      <line nr="10" mi="0" ci="2"/>
      <line nr="11" mi="1" ci="0"/>
      <line nr="12" mi="0" ci="0"/>
    </sourcefile>
    <sourcefile name="Generated.java">
      <line nr="5" mi="1" ci="0"/>
    </sourcefile>
  </package>
</report>`;

function diffFor(path, start, count) {
  return [
    `diff --git a/${path} b/${path}`,
    "index 000..111 100644",
    `--- a/${path}`,
    `+++ b/${path}`,
    `@@ -1,0 +${start},${count} @@`,
    ...Array.from({ length: count }, () => "+line"),
    "",
  ].join("\n");
}

test("parseJacocoXml maps executable lines and ignores comments", () => {
  const coverage = parseJacocoXml(XML);
  const demo = coverage.get("com/awap/demo/Demo.java");
  assert.equal(demo.get(10), true);
  assert.equal(demo.get(11), false);
  assert.equal(demo.has(12), false);
});

test("a declaration-only interface has no executable lines and is skipped", () => {
  const xml = `<report name="awap">
  <package name="com/awap/demo">
    <sourcefile name="Api.java"/>
    <class name="com/awap/demo/Api"/>
  </package>
</report>`;
  const coverage = parseJacocoXml(xml);
  assert.deepEqual([...coverage.get("com/awap/demo/Api.java").keys()], []);

  const path = "backend/identity/src/main/java/com/awap/demo/Api.java";
  const changed = new Map([[path, new Set([4, 5, 6])]]);
  const result = evaluateChangedLines(changed, coverage, 100);
  assert.equal(result.passed, true);
  assert.equal(result.executable, 0);
  assert.equal(result.files.length, 0);
});

test("parseDiff collects added lines for relevant Java files only", () => {
  const path = "backend/identity/src/main/java/com/awap/demo/Demo.java";
  const changed = parseDiff(diffFor(path, 10, 3));
  assert.deepEqual([...changed.get(path)].sort((a, b) => a - b), [10, 11, 12]);
});

test("generated DTO and config paths are excluded explicitly", () => {
  assert.equal(isExcluded("backend/identity/src/main/java/com/awap/demo/interfaces/dto/Foo.java"), true);
  assert.equal(isExcluded("backend/identity/src/main/java/com/awap/demo/config/Foo.java"), true);
  assert.equal(isExcluded("backend/identity/src/main/java/com/awap/demo/Demo.java"), false);
});

test("fully covered changed lines pass the gate", () => {
  const path = "backend/identity/src/main/java/com/awap/demo/Demo.java";
  const changed = new Map([[path, new Set([10])]]);
  const result = evaluateChangedLines(changed, parseJacocoXml(XML), 100);
  assert.equal(result.passed, true);
  assert.equal(result.percentage, 100);
  assert.equal(result.executable, 1);
});

test("an uncovered changed line fails the gate", () => {
  const path = "backend/identity/src/main/java/com/awap/demo/Demo.java";
  const changed = new Map([[path, new Set([10, 11])]]);
  const result = evaluateChangedLines(changed, parseJacocoXml(XML), 100);
  assert.equal(result.passed, false);
  assert.equal(result.percentage, 50);
  assert.deepEqual(result.files[0].uncovered, [11]);
});

test("a changed line without any JaCoCo execution is treated as uncovered", () => {
  const path = "backend/identity/src/main/java/com/awap/demo/Missing.java";
  const changed = new Map([[path, new Set([5])]]);
  const result = evaluateChangedLines(changed, parseJacocoXml(XML), 100);
  assert.equal(result.passed, false);
  assert.equal(result.executable, 1);
  assert.equal(result.covered, 0);
});

test("non-executable and generated lines do not affect the gate", () => {
  const demo = "backend/identity/src/main/java/com/awap/demo/Demo.java";
  const dto = "backend/identity/src/main/java/com/awap/demo/interfaces/dto/Foo.java";
  const changed = new Map([
    [demo, new Set([12])],
    [dto, new Set([5])],
  ]);
  const result = evaluateChangedLines(changed, parseJacocoXml(XML), 100);
  assert.equal(result.passed, true);
  assert.equal(result.executable, 0);
});

test("an empty change set passes", () => {
  const result = evaluateChangedLines(new Map(), new Map(), 100);
  assert.equal(result.passed, true);
  assert.equal(result.percentage, 100);
});
