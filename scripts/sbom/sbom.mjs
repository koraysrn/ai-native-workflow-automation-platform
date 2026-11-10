#!/usr/bin/env node
// CycloneDX SBOM validation, merge and license policy for all runtime layers (issue #193).
//
// The backend SBOM is produced by the CycloneDX Maven plugin; the frontend and AI-layer SBOMs are
// produced by Syft. This module is the deterministic, testable part: every SBOM must be structurally
// valid before it can be merged, the merged inventory must contain all three layers, and a
// disallowed license fails the release.
//
// Usage:
//   node scripts/sbom/sbom.mjs validate --file sbom/backend.cdx.json --layer backend
//   node scripts/sbom/sbom.mjs merge --backend b.json --frontend f.json --ai a.json --output sbom/awap-sbom.cdx.json
//   node scripts/sbom/sbom.mjs licenses --file sbom/awap-sbom.cdx.json

import { readFile, writeFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

const SUPPORTED_SPEC_VERSIONS = ["1.4", "1.5", "1.6"];
const LAYERS = ["backend", "frontend", "ai-layer"];

export class SbomValidationError extends Error {
  constructor(messages) {
    super(`Invalid CycloneDX SBOM: ${messages.join("; ")}`);
    this.name = "SbomValidationError";
    this.messages = messages;
  }
}

/** Structural validation of the CycloneDX fields this pipeline relies on. */
export function validateSbom(document, { layer } = {}) {
  const errors = [];
  if (document?.bomFormat !== "CycloneDX") {
    errors.push("bomFormat must be CycloneDX");
  }
  if (!SUPPORTED_SPEC_VERSIONS.includes(String(document?.specVersion))) {
    errors.push(`specVersion must be one of ${SUPPORTED_SPEC_VERSIONS.join(", ")}`);
  }
  if (!/^urn:uuid:[0-9a-f-]{36}$/i.test(document?.serialNumber ?? "")) {
    errors.push("serialNumber must be a urn:uuid value");
  }
  if (!document?.metadata?.timestamp) {
    errors.push("metadata.timestamp is required");
  }
  if (layer && !document?.metadata?.component?.name && !document?.metadata?.component) {
    errors.push(`metadata.component is required for the ${layer} layer`);
  }
  if (!Array.isArray(document?.components) || document.components.length === 0) {
    errors.push("components must be a non-empty array");
    throw new SbomValidationError(errors);
  }
  document.components.forEach((component, index) => {
    const label = component?.name ?? `components[${index}]`;
    if (!component?.type) {
      errors.push(`${label}: component type is required`);
    }
    if (!component?.name) {
      errors.push(`components[${index}]: component name is required`);
    }
    // Syft also inventories non-package files (workflow YAML, lockfiles, binaries); only
    // package-like components need a version or purl.
    const packageLike = ["library", "framework", "application", "container"].includes(
      component?.type,
    );
    if (packageLike && !component?.version && !component?.purl) {
      errors.push(`${label}: version or purl is required`);
    }
    for (const license of component?.licenses ?? []) {
      if (!license?.license && !license?.expression) {
        errors.push(`${label}: license entry needs license or expression`);
      }
    }
  });
  if (errors.length > 0) {
    throw new SbomValidationError(errors);
  }
  return document;
}

/** Stable identity for de-duplication across layer inventories. */
export function componentKey(component) {
  return component?.purl ?? `${component?.type ?? "unknown"}:${component?.name}@${component?.version ?? ""}`;
}

/** Merges the layer SBOMs into one release inventory, de-duplicating components. */
export function mergeSboms(documents, { version = "0.0.0", timestamp = new Date().toISOString() } = {}) {
  const components = new Map();
  const layerCounts = new Map();
  for (const [layer, document] of Object.entries(documents)) {
    validateSbom(document, { layer });
    let count = 0;
    for (const component of document.components) {
      const key = componentKey(component);
      if (!components.has(key)) {
        components.set(key, component);
        count += 1;
      }
    }
    layerCounts.set(layer, count);
  }
  return {
    bomFormat: "CycloneDX",
    specVersion: "1.6",
    serialNumber: `urn:uuid:${crypto.randomUUID()}`,
    version: 1,
    metadata: {
      timestamp,
      component: {
        type: "application",
        name: "awap",
        version,
      },
      properties: [
        { name: "awap:layers", value: [...layerCounts.keys()].join(",") },
        ...[...layerCounts.entries()].map(([layer, count]) => ({
          name: `awap:layer:${layer}:components`,
          value: String(count),
        })),
      ],
    },
    components: [...components.values()],
  };
}

/** Extracts the SPDX license identifiers from a component. */
export function licenseIds(component) {
  const ids = [];
  for (const entry of component?.licenses ?? []) {
    if (entry?.license?.id) {
      ids.push(entry.license.id);
    } else if (entry?.license?.name) {
      ids.push(entry.license.name);
    } else if (entry?.expression) {
      ids.push(entry.expression);
    }
  }
  return ids;
}

function expressionIsAllowed(expression, policy) {
  // SPDX expressions use AND/OR/WITH and parentheses; a denied identifier anywhere in the
  // expression (e.g. "MIT OR GPL-3.0-only") is release-blocking.
  const tokens = expression
    .replace(/[()]/g, " ")
    .split(/\s+/)
    .filter((token) => token && !/^(AND|OR|WITH)$/i.test(token));
  return !tokens.some((token) => policy.deny.includes(token));
}

/**
 * Applies the license policy. Disallowed licenses are release-blocking; components without
 * license data are reported for manual review but do not fail the build (many SBOM generators omit
 * license metadata for private or build-only packages).
 */
export function checkLicenses(document, policy) {
  const allowed = new Set(policy.allow);
  const denied = new Set(policy.deny);
  const result = { allowed: 0, missing: [], disallowed: [] };
  for (const component of document?.components ?? []) {
    const ids = licenseIds(component);
    if (ids.length === 0) {
      result.missing.push(component.name ?? "unknown");
      continue;
    }
    const ok = ids.every((id) => {
      if (allowed.has(id)) {
        return true;
      }
      if (denied.has(id)) {
        return false;
      }
      return expressionIsAllowed(id, policy);
    });
    if (ok) {
      result.allowed += 1;
    } else {
      result.disallowed.push({ component: component.name ?? "unknown", licenses: ids });
    }
  }
  return result;
}

export function parseArguments(argv) {
  const command = argv[0];
  const options = {};
  for (let index = 1; index < argv.length; index += 1) {
    if (argv[index].startsWith("--")) {
      options[argv[index].slice(2)] = argv[index + 1];
      index += 1;
    }
  }
  return { command, options };
}

async function load(file) {
  return JSON.parse(await readFile(file, "utf8"));
}

export async function main(argv = process.argv.slice(2)) {
  const { command, options } = parseArguments(argv);
  try {
    if (command === "validate") {
      const document = await load(options.file);
      validateSbom(document, { layer: options.layer });
      console.log(`Valid CycloneDX SBOM: ${options.file} (${document.components.length} components)`);
      return 0;
    }
    if (command === "merge") {
      const documents = {
        backend: await load(options.backend),
        frontend: await load(options.frontend),
        "ai-layer": await load(options.ai),
      };
      const merged = mergeSboms(documents, { version: options.version ?? "0.0.0" });
      await writeFile(options.output, `${JSON.stringify(merged, null, 2)}\n`);
      console.log(
        `Merged ${merged.components.length} components from ${LAYERS.length} layers into ${options.output}`,
      );
      return 0;
    }
    if (command === "licenses") {
      const document = await load(options.file);
      const policy = await load(options.policy ?? "scripts/sbom/license-policy.json");
      const result = checkLicenses(document, policy);
      result.missing.forEach((name) => console.warn(`WARN missing license metadata: ${name}`));
      if (result.disallowed.length > 0) {
        console.error("Disallowed licenses detected:");
        result.disallowed.forEach((entry) =>
          console.error(`  - ${entry.component}: ${entry.licenses.join(", ")}`),
        );
        return 1;
      }
      console.log(`License policy passed (${result.allowed} allowed, ${result.missing.length} missing warnings).`);
      return 0;
    }
    console.error(
      "Usage: node scripts/sbom/sbom.mjs <validate|merge|licenses> [--file|--backend|--frontend|--ai|--output]",
    );
    return 2;
  } catch (error) {
    console.error(error.message);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().then((code) => {
    process.exitCode = code;
  });
}
