#!/usr/bin/env node
/**
 * Backend module dependency graph check (issue #181).
 *
 * The backend is a package-level modular monolith: `com.awap.<module>` with
 * `domain`, `application`, `infrastructure` and `interfaces` layers. This script
 * builds the inter-module dependency graph from Java imports, reports cycles and
 * can fail the build when a cycle appears that is not in the documented baseline.
 *
 * Usage:
 *   node scripts/module-dependency-graph.mjs              # human-readable report
 *   node scripts/module-dependency-graph.mjs --check      # fail on new/stale cycles
 *   node scripts/module-dependency-graph.mjs --json       # machine-readable graph
 *   node scripts/module-dependency-graph.mjs --dot        # Graphviz output
 */

import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { pathToFileURL } from "node:url";

const DEFAULT_SOURCE_ROOTS = [
  "backend/identity/src/main/java",
  "backend/common/src/main/java",
];
const DEFAULT_BASELINE = "scripts/module-cycles-baseline.json";

/** Reads every `.java` file under the given roots. */
export function collectJavaFiles(roots = DEFAULT_SOURCE_ROOTS, cwd = process.cwd()) {
  const files = [];
  const walk = (dir) => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (entry.isFile() && entry.name.endsWith(".java")) {
        files.push(full);
      }
    }
  };
  for (const root of roots) {
    walk(join(cwd, root));
  }
  return files.sort();
}

/** Derives the `com.awap.<module>` module name from an absolute file path. */
export function moduleOfFile(file) {
  const normalized = file.split(sep).join("/");
  const marker = "/com/awap/";
  const index = normalized.lastIndexOf(marker);
  if (index === -1) {
    return undefined;
  }
  return normalized.slice(index + marker.length).split("/")[0] || undefined;
}

/** Extracts inter-module edges (source module -> set of imported modules). */
export function buildModuleGraph(files) {
  const graph = new Map();
  for (const file of files) {
    const source = moduleOfFile(file);
    if (!source) {
      continue;
    }
    if (!graph.has(source)) {
      graph.set(source, new Set());
    }
    const contents = readFileSync(file, "utf8");
    const importPattern = /^\s*import\s+com\.awap\.([a-z][a-z0-9_]*)\b/gm;
    let match;
    while ((match = importPattern.exec(contents)) !== null) {
      const target = match[1];
      if (target !== source) {
        graph.get(source).add(target);
      }
    }
  }
  return graph;
}

/** Returns strongly connected components (Tarjan) that contain a cycle. */
export function findCycles(graph) {
  let index = 0;
  const indexes = new Map();
  const lowLinks = new Map();
  const stack = [];
  const onStack = new Set();
  const cycles = [];

  const visit = (node) => {
    indexes.set(node, index);
    lowLinks.set(node, index);
    index += 1;
    stack.push(node);
    onStack.add(node);

    for (const next of graph.get(node) ?? []) {
      if (!indexes.has(next)) {
        visit(next);
        lowLinks.set(node, Math.min(lowLinks.get(node), lowLinks.get(next)));
      } else if (onStack.has(next)) {
        lowLinks.set(node, Math.min(lowLinks.get(node), indexes.get(next)));
      }
    }

    if (lowLinks.get(node) === indexes.get(node)) {
      const component = [];
      let member;
      do {
        member = stack.pop();
        onStack.delete(member);
        component.push(member);
      } while (member !== node);
      if (component.length > 1) {
        cycles.push(component.sort());
      }
    }
  };

  for (const node of [...graph.keys()].sort()) {
    if (!indexes.has(node)) {
      visit(node);
    }
  }
  return cycles.sort((a, b) => a.join(",").localeCompare(b.join(",")));
}

/** Canonical form used to compare a cycle with the baseline. */
export function cycleKey(cycle) {
  return [...cycle].sort().join(",");
}

export function normalizeBaseline(baseline) {
  const entries = Array.isArray(baseline) ? baseline : (baseline.allowedCycles ?? []);
  return new Set(entries.map((cycle) => cycleKey(cycle)));
}

/**
 * Returns `{ newCycles, resolvedCycles }` relative to the baseline. A resolved
 * cycle means the baseline is stale and must be pruned so the ratchet tightens.
 */
export function diffCycles(cycles, baseline) {
  const allowed = normalizeBaseline(baseline);
  const actual = new Set(cycles.map(cycleKey));
  const newCycles = cycles.filter((cycle) => !allowed.has(cycleKey(cycle)));
  const resolvedCycles = [...allowed]
    .filter((key) => !actual.has(key))
    .map((key) => key.split(","));
  return { newCycles, resolvedCycles };
}

export function toDot(graph) {
  const lines = ["digraph awap_modules {", "  rankdir=LR;"];
  for (const [source, targets] of [...graph.entries()].sort()) {
    lines.push(`  "${source}";`);
    for (const target of [...targets].sort()) {
      lines.push(`  "${source}" -> "${target}";`);
    }
  }
  lines.push("}");
  return `${lines.join("\n")}\n`;
}

export function toJson(graph, cycles) {
  return JSON.stringify(
    {
      modules: [...graph.keys()].sort(),
      edges: Object.fromEntries(
        [...graph.entries()]
          .sort(([a], [b]) => a.localeCompare(b))
          .map(([source, targets]) => [source, [...targets].sort()]),
      ),
      cycles,
    },
    null,
    2,
  );
}

export function loadBaseline(path = DEFAULT_BASELINE, cwd = process.cwd()) {
  try {
    return JSON.parse(readFileSync(join(cwd, path), "utf8"));
  } catch {
    return { allowedCycles: [] };
  }
}

export function main(argv = process.argv.slice(2), cwd = process.cwd(), log = console.log) {
  const files = collectJavaFiles(DEFAULT_SOURCE_ROOTS, cwd);
  const graph = buildModuleGraph(files);
  const cycles = findCycles(graph);

  if (argv.includes("--json")) {
    log(toJson(graph, cycles));
    return 0;
  }
  if (argv.includes("--dot")) {
    log(toDot(graph));
    return 0;
  }

  log(`Backend modules: ${[...graph.keys()].sort().join(", ")}`);
  for (const [source, targets] of [...graph.entries()].sort()) {
    log(`  ${source} -> ${[...targets].sort().join(", ") || "(none)"}`);
  }

  if (cycles.length === 0) {
    log("No module dependency cycles detected.");
    return 0;
  }

  log(`Detected ${cycles.length} cycle group(s):`);
  for (const cycle of cycles) {
    log(`  ${cycle.join(" <-> ")}`);
  }

  if (!argv.includes("--check")) {
    return 0;
  }

  const baseline = loadBaseline(DEFAULT_BASELINE, cwd);
  const { newCycles, resolvedCycles } = diffCycles(cycles, baseline);
  if (newCycles.length > 0) {
    log("");
    log("New module cycles are not allowed:");
    for (const cycle of newCycles) {
      log(`  ${cycle.join(" <-> ")}`);
    }
  }
  if (resolvedCycles.length > 0) {
    log("");
    log("The baseline contains cycles that no longer exist; prune the entries:");
    for (const cycle of resolvedCycles) {
      log(`  ${cycle.join(",")}`);
    }
  }
  return newCycles.length > 0 || resolvedCycles.length > 0 ? 1 : 0;
}

const invokedDirectly =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedDirectly) {
  process.exitCode = main();
}
