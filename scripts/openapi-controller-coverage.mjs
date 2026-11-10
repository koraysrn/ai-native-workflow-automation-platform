#!/usr/bin/env node
// Compares the Spring controller mappings with the OpenAPI contract (issue #192).
//
// The inventory is derived from the controller annotations, so a new endpoint or a changed path
// cannot silently drift out of docs/openapi/awap-openapi.yaml. Run with `--check` in CI; `--json`
// prints the machine-readable inventory used by the breaking-change check.

import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";
import { parse } from "yaml";

const HTTP_METHODS = ["get", "post", "put", "patch", "delete"];

/** Extracts the first string literal from an annotation argument list. */
export function firstStringLiteral(argumentsText) {
  if (!argumentsText) {
    return "";
  }
  const match = argumentsText.match(/"([^"]*)"/);
  return match ? match[1] : "";
}

/** Returns the path in a mapping annotation (`@GetMapping("/x")`, `value = "/x"`, `path = "/x"`). */
export function mappingPath(argumentsText) {
  if (!argumentsText) {
    return "";
  }
  const named = argumentsText.match(/(?:value|path)\s*=\s*(?:\{[^}]*?"([^"]*)"|"([^"]*)")/);
  if (named) {
    return named[1] ?? named[2] ?? "";
  }
  return firstStringLiteral(argumentsText);
}

function joinPaths(base, path) {
  const left = base.endsWith("/") ? base.slice(0, -1) : base;
  const right = path.startsWith("/") ? path : `/${path}`;
  const combined = `${left}${right}`;
  return combined.length > 1 && combined.endsWith("/") ? combined.slice(0, -1) : combined;
}

/**
 * Extracts endpoints from one Java controller source.
 *
 * Supported forms:
 *   @RequestMapping("/base") on the class
 *   @GetMapping / @PostMapping / @PutMapping / @PatchMapping / @DeleteMapping
 *   @RequestMapping(value = "/x", method = RequestMethod.POST) on a method
 */
export function extractControllerEndpoints(source) {
  const endpoints = [];
  const classMatch = source.match(/@RequestMapping\s*\(([^)]*)\)\s*(?:public\s+)?class\s+(\w+)/);
  if (!classMatch) {
    return endpoints;
  }
  const basePath = mappingPath(classMatch[1]);
  const className = classMatch[2];
  const methodPattern =
    /@(Get|Post|Put|Patch|Delete)Mapping\s*(?:\(([^)]*)\))?|@RequestMapping\s*\(([^)]*)\)/g;
  let match;
  while ((match = methodPattern.exec(source)) !== null) {
    const shorthand = match[1];
    const args = match[2] ?? match[3] ?? "";
    let method;
    let path = mappingPath(args);
    if (shorthand) {
      method = shorthand.toLowerCase();
    } else {
      const methodMatch = args.match(/RequestMethod\.(GET|POST|PUT|PATCH|DELETE)/);
      if (!methodMatch) {
        continue;
      }
      method = methodMatch[1].toLowerCase();
    }
    endpoints.push({
      method,
      path: joinPaths(basePath, path),
      controller: className,
    });
  }
  return endpoints;
}

/** Returns all path/method pairs declared by an OpenAPI document. */
export function extractSpecEndpoints(document) {
  const endpoints = new Set();
  for (const [path, item] of Object.entries(document?.paths ?? {})) {
    for (const method of HTTP_METHODS) {
      if (item && typeof item === "object" && item[method]) {
        endpoints.add(`${method.toUpperCase()} ${path}`);
      }
    }
  }
  return endpoints;
}

/** Returns the controller endpoints missing from the spec and spec endpoints with no controller. */
/** Operational endpoints (actuator, probes) are documented but are not controllers. */
export function isControllerContractPath(path) {
  return path.startsWith("/api/");
}

export function compareEndpoints(controllerEndpoints, specEndpoints) {
  const controller = new Set(
    controllerEndpoints.map((endpoint) => `${endpoint.method.toUpperCase()} ${endpoint.path}`),
  );
  const missingFromSpec = [...controller].filter((key) => !specEndpoints.has(key)).sort();
  const staleInSpec = [...specEndpoints]
    .filter((key) => isControllerContractPath(key.split(" ")[1] ?? ""))
    .filter((key) => !controller.has(key))
    .sort();
  return { missingFromSpec, staleInSpec, controllerCount: controller.size, specCount: specEndpoints.size };
}

/** Loads every controller under the backend tree. */
export async function collectControllerEndpoints(root = "backend/identity/src/main/java") {
  const files = [];
  async function walk(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        await walk(path);
      } else if (entry.name.endsWith("Controller.java")) {
        files.push(path);
      }
    }
  }
  await walk(root);
  const endpoints = [];
  for (const file of files.sort()) {
    const source = await readFile(file, "utf8");
    for (const endpoint of extractControllerEndpoints(source)) {
      endpoints.push({ ...endpoint, file });
    }
  }
  return endpoints;
}

export async function main(argv = process.argv.slice(2)) {
  const specPath = argv.find((arg) => arg.endsWith(".yaml") || arg.endsWith(".yml")) ??
    "docs/openapi/awap-openapi.yaml";
  const document = parse(await readFile(specPath, "utf8"));
  const controllers = await collectControllerEndpoints();
  const comparison = compareEndpoints(controllers, extractSpecEndpoints(document));
  if (argv.includes("--json")) {
    console.log(JSON.stringify({ controllers, ...comparison }, null, 2));
    return 0;
  }
  if (comparison.missingFromSpec.length > 0) {
    console.error("Controller endpoints missing from the OpenAPI spec:");
    comparison.missingFromSpec.forEach((endpoint) => console.error(`  - ${endpoint}`));
  }
  if (comparison.staleInSpec.length > 0) {
    console.error("OpenAPI paths without a controller mapping:");
    comparison.staleInSpec.forEach((endpoint) => console.error(`  - ${endpoint}`));
  }
  if (comparison.missingFromSpec.length === 0 && comparison.staleInSpec.length === 0) {
    console.log(
      `OpenAPI contract matches ${comparison.controllerCount} controller endpoint(s).`,
    );
    return 0;
  }
  return 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().then((code) => {
    process.exitCode = code;
  });
}
