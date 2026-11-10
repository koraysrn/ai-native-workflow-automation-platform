#!/usr/bin/env node
// Blocks breaking changes to the published OpenAPI contract (issue #192).
//
// The check compares the base branch document with the current document and reports only
// client-visible narrowing: removed paths/methods/status codes, changed types, removed enum values,
// a new required request property, a removed response property and removed security. Additive
// changes (new paths, optional properties, new response codes) pass.
//
// Usage:
//   node scripts/openapi-breaking-change.mjs --base base.yaml --current docs/openapi/awap-openapi.yaml

import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";
import { parse } from "yaml";

const HTTP_METHODS = ["get", "post", "put", "patch", "delete"];

/** Resolves a local `#/components/...` reference; returns the input when it is inline. */
export function resolveSchema(document, schema, seen = new Set()) {
  if (!schema || typeof schema !== "object" || !schema.$ref) {
    return schema;
  }
  if (!schema.$ref.startsWith("#/")) {
    return schema;
  }
  if (seen.has(schema.$ref)) {
    return {};
  }
  seen.add(schema.$ref);
  const path = schema.$ref.slice(2).split("/");
  let current = document;
  for (const segment of path) {
    current = current?.[segment];
  }
  return resolveSchema(document, current, seen);
}

function schemaName(schema) {
  return schema?.$ref ?? schema?.type ?? "schema";
}

/** Compares two schemas in a request or response context and returns breaking findings. */
export function compareSchemas(baseDocument, currentDocument, baseSchema, currentSchema, context, where) {
  const base = resolveSchema(baseDocument, baseSchema);
  const current = resolveSchema(currentDocument, currentSchema);
  if (!base || !current) {
    return [];
  }
  const findings = [];
  if (base.type && current.type && base.type !== current.type) {
    findings.push(`${where}: type changed from ${base.type} to ${current.type}`);
  }
  if (Array.isArray(base.enum)) {
    const removed = base.enum.filter((value) => !(current.enum ?? []).includes(value));
    if (removed.length > 0) {
      findings.push(`${where}: enum value(s) removed: ${removed.join(", ")}`);
    }
  }
  const baseRequired = new Set(base.required ?? []);
  const currentRequired = new Set(current.required ?? []);
  if (context === "request") {
    const added = [...currentRequired].filter((property) => !baseRequired.has(property));
    if (added.length > 0) {
      findings.push(`${where}: new required request property/properties: ${added.join(", ")}`);
    }
  } else {
    const removedRequired = [...baseRequired].filter((property) => !currentRequired.has(property));
    if (removedRequired.length > 0) {
      findings.push(
        `${where}: response property/properties no longer guaranteed: ${removedRequired.join(", ")}`,
      );
    }
  }
  for (const [property, baseProperty] of Object.entries(base.properties ?? {})) {
    const currentProperty = (current.properties ?? {})[property];
    if (!currentProperty) {
      if (context === "response") {
        findings.push(`${where}.${property}: response property removed`);
      } else if (baseRequired.has(property)) {
        findings.push(`${where}.${property}: required request property removed`);
      }
      continue;
    }
    findings.push(
      ...compareSchemas(
        baseDocument,
        currentDocument,
        baseProperty,
        currentProperty,
        context,
        `${where}.${property}`,
      ),
    );
  }
  if (base.items) {
    findings.push(
      ...compareSchemas(
        baseDocument,
        currentDocument,
        base.items,
        current.items,
        context,
        `${where}[]`,
      ),
    );
  }
  return findings;
}

/** Returns every client-visible breaking change between two documents. */
export function breakingChanges(baseDocument, currentDocument) {
  const findings = [];
  for (const [path, basePath] of Object.entries(baseDocument.paths ?? {})) {
    const currentPath = currentDocument.paths?.[path];
    if (!currentPath) {
      findings.push(`path removed: ${path}`);
      continue;
    }
    for (const method of HTTP_METHODS) {
      const baseOperation = basePath?.[method];
      if (!baseOperation) {
        continue;
      }
      const currentOperation = currentPath[method];
      if (!currentOperation) {
        findings.push(`${method.toUpperCase()} ${path}: operation removed`);
        continue;
      }
      const where = `${method.toUpperCase()} ${path}`;
      for (const baseParameter of baseOperation.parameters ?? []) {
        const key = `${baseParameter.in}:${baseParameter.name}`;
        const stillThere = (currentOperation.parameters ?? []).some(
          (parameter) => `${parameter.in}:${parameter.name}` === key,
        );
        if (!stillThere && baseParameter.required) {
          findings.push(`${where}: required parameter removed (${key})`);
        }
      }
      const baseRequestBody = resolveSchema(
        baseDocument,
        baseOperation.requestBody?.content?.["application/json"]?.schema,
      );
      const currentRequestBody = resolveSchema(
        currentDocument,
        currentOperation.requestBody?.content?.["application/json"]?.schema,
      );
      if (baseRequestBody) {
        if (!currentRequestBody) {
          findings.push(`${where}: request body removed`);
        } else {
          findings.push(
            ...compareSchemas(
              baseDocument,
              currentDocument,
              baseRequestBody,
              currentRequestBody,
              "request",
              `${where} request`,
            ),
          );
        }
      }
      for (const [status, baseResponse] of Object.entries(baseOperation.responses ?? {})) {
        const currentResponse = currentOperation.responses?.[status];
        if (!currentResponse) {
          findings.push(`${where}: response ${status} removed`);
          continue;
        }
        const baseSchema = resolveSchema(
          baseDocument,
          baseResponse.content?.["application/json"]?.schema ??
            baseResponse.content?.["application/problem+json"]?.schema,
        );
        const currentSchema = resolveSchema(
          currentDocument,
          currentResponse.content?.["application/json"]?.schema ??
            currentResponse.content?.["application/problem+json"]?.schema,
        );
        if (baseSchema) {
          if (!currentSchema) {
            findings.push(`${where}: response ${status} body removed`);
          } else {
            findings.push(
              ...compareSchemas(
                baseDocument,
                currentDocument,
                baseSchema,
                currentSchema,
                "response",
                `${where} ${status}`,
              ),
            );
          }
        }
      }
      if ((baseOperation.security ?? []).length > 0 && (currentOperation.security ?? []).length === 0) {
        findings.push(`${where}: operation security removed`);
      }
    }
  }
  return findings;
}

export async function checkBreakingChange(basePath, currentPath) {
  const base = parse(await readFile(basePath, "utf8"));
  const current = parse(await readFile(currentPath, "utf8"));
  return breakingChanges(base, current);
}

async function main(argv = process.argv.slice(2)) {
  const baseIndex = argv.indexOf("--base");
  if (baseIndex < 0 || !argv[baseIndex + 1]) {
    console.error("Usage: node scripts/openapi-breaking-change.mjs --base <file> [--current <file>]");
    return 2;
  }
  const currentIndex = argv.indexOf("--current");
  const currentPath =
    currentIndex >= 0 && argv[currentIndex + 1]
      ? argv[currentIndex + 1]
      : "docs/openapi/awap-openapi.yaml";
  try {
    const findings = await checkBreakingChange(argv[baseIndex + 1], currentPath);
    if (findings.length > 0) {
      console.error("Breaking OpenAPI changes detected:");
      findings.forEach((finding) => console.error(`  - ${finding}`));
      return 1;
    }
    console.log("No breaking OpenAPI changes detected.");
    return 0;
  } catch (error) {
    console.error(`OpenAPI breaking-change check failed: ${error.message}`);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().then((code) => {
    process.exitCode = code;
  });
}
