import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  buildModuleGraph,
  collectJavaFiles,
  cycleKey,
  diffCycles,
  findCycles,
  main,
  moduleOfFile,
  normalizeBaseline,
  toDot,
  toJson,
} from "../module-dependency-graph.mjs";

function fixtureProject(files) {
  const root = mkdtempSync(join(tmpdir(), "awap-modules-"));
  for (const [relativePath, contents] of Object.entries(files)) {
    const full = join(root, relativePath);
    mkdirSync(join(full, ".."), { recursive: true });
    writeFileSync(full, contents, "utf8");
  }
  return root;
}

test("moduleOfFile extracts the module after com/awap", () => {
  assert.equal(
    moduleOfFile("/repo/backend/identity/src/main/java/com/awap/engine/Application.java"),
    "engine",
  );
  assert.equal(moduleOfFile("/repo/not-a-java-module/Foo.java"), undefined);
});

test("buildModuleGraph records only cross-module imports", () => {
  const root = fixtureProject({
    "backend/identity/src/main/java/com/awap/engine/A.java":
      "package com.awap.engine;\nimport com.awap.task.application.TaskService;\nimport com.awap.engine.domain.Node;\n",
    "backend/identity/src/main/java/com/awap/task/B.java":
      "package com.awap.task;\nimport com.awap.common.api.Api;\n",
    "backend/common/src/main/java/com/awap/common/api/Api.java":
      "package com.awap.common.api;\n",
  });
  try {
    const graph = buildModuleGraph(
      collectJavaFiles([
        "backend/identity/src/main/java",
        "backend/common/src/main/java",
      ], root),
    );
    assert.deepEqual([...graph.get("engine")], ["task"]);
    assert.deepEqual([...graph.get("task")], ["common"]);
    assert.deepEqual([...graph.get("common")], []);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("findCycles detects a two-module cycle and ignores a DAG", () => {
  const graph = new Map([
    ["a", new Set(["b"])],
    ["b", new Set(["a"])],
    ["c", new Set(["a"])],
  ]);
  assert.deepEqual(findCycles(graph), [["a", "b"]]);

  const dag = new Map([
    ["a", new Set(["b"])],
    ["b", new Set(["c"])],
    ["c", new Set()],
  ]);
  assert.deepEqual(findCycles(dag), []);
});

test("diffCycles distinguishes new and resolved cycles", () => {
  const cycles = [["engine", "task"], ["identity", "workflow", "organization"]];
  const baseline = { allowedCycles: [["task", "engine"], ["identity", "organization", "workflow"]] };
  assert.deepEqual(diffCycles(cycles, baseline), { newCycles: [], resolvedCycles: [] });

  const drifted = diffCycles([["engine", "task"], ["ai", "usage"]], baseline);
  assert.equal(drifted.newCycles.length, 1);
  assert.deepEqual(drifted.newCycles, [["ai", "usage"]]);
  assert.deepEqual(drifted.resolvedCycles, [["identity", "organization", "workflow"]]);
});

test("cycleKey and normalizeBaseline are order-independent", () => {
  assert.equal(cycleKey(["b", "a"]), "a,b");
  const allowed = normalizeBaseline({ allowedCycles: [["b", "a"]] });
  assert.ok(allowed.has("a,b"));
});

test("toDot and toJson render deterministic output", () => {
  const graph = new Map([
    ["b", new Set(["a"])],
    ["a", new Set()],
  ]);
  assert.match(toDot(graph), /"b" -> "a";/);
  const parsed = JSON.parse(toJson(graph, []));
  assert.deepEqual(parsed.modules, ["a", "b"]);
  assert.deepEqual(parsed.edges, { a: [], b: ["a"] });
});

test("main fails --check when a cycle is not in the baseline", () => {
  const root = fixtureProject({
    "backend/identity/src/main/java/com/awap/a/A.java":
      "package com.awap.a;\nimport com.awap.b.B;\n",
    "backend/identity/src/main/java/com/awap/b/B.java":
      "package com.awap.b;\nimport com.awap.a.A;\n",
    "scripts/module-cycles-baseline.json": JSON.stringify({ allowedCycles: [] }),
  });
  try {
    const lines = [];
    const code = main(["--check"], root, (line) => lines.push(line));
    assert.equal(code, 1);
    assert.ok(lines.some((line) => line.includes("New module cycles are not allowed")));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("main passes --check when the cycle matches the baseline", () => {
  const root = fixtureProject({
    "backend/identity/src/main/java/com/awap/a/A.java":
      "package com.awap.a;\nimport com.awap.b.B;\n",
    "backend/identity/src/main/java/com/awap/b/B.java":
      "package com.awap.b;\nimport com.awap.a.A;\n",
    "scripts/module-cycles-baseline.json": JSON.stringify({ allowedCycles: [["a", "b"]] }),
  });
  try {
    const code = main(["--check"], root, () => {});
    assert.equal(code, 0);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
