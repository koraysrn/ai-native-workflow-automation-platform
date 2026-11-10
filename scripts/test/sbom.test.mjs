import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import {
  SbomValidationError,
  checkLicenses,
  componentKey,
  licenseIds,
  main,
  mergeSboms,
  parseArguments,
  validateSbom,
} from "../sbom/sbom.mjs";

const POLICY = {
  allow: ["MIT", "Apache-2.0", "BSD-3-Clause"],
  deny: ["GPL-3.0-only", "AGPL-3.0-only"],
};

function component(name, version = "1.0.0", licenses = [{ license: { id: "MIT" } }], type = "library") {
  return {
    type,
    name,
    version,
    purl: `pkg:npm/${name}@${version}`,
    ...(licenses ? { licenses } : {}),
  };
}

function bom(components, overrides = {}) {
  return {
    bomFormat: "CycloneDX",
    specVersion: "1.6",
    serialNumber: `urn:uuid:${randomUUID()}`,
    version: 1,
    metadata: { timestamp: new Date().toISOString(), component: { type: "application", name: "layer" } },
    components,
    ...overrides,
  };
}

test("validates a minimal CycloneDX document", () => {
  const document = bom([component("alpha")]);
  assert.equal(validateSbom(document, { layer: "backend" }), document);
});

test("rejects a missing bomFormat, unsupported spec, serial, timestamp and empty components", () => {
  assert.throws(
    () => validateSbom(bom([component("a")], { bomFormat: "SPDX" })),
    /bomFormat must be CycloneDX/,
  );
  assert.throws(
    () => validateSbom(bom([component("a")], { specVersion: "1.2" })),
    /specVersion must be one of/,
  );
  assert.throws(
    () => validateSbom(bom([component("a")], { serialNumber: "not-a-uuid" })),
    /serialNumber must be a urn:uuid/,
  );
  assert.throws(
    () => validateSbom(bom([component("a")], { metadata: { component: { name: "x" } } })),
    /metadata.timestamp is required/,
  );
  assert.throws(() => validateSbom(bom([])), /components must be a non-empty array/);
});

test("accepts a Syft file component without a version or purl", () => {
  assert.doesNotThrow(() =>
    validateSbom(bom([{ type: "file", name: "frontend/pnpm-lock.yaml" }])),
  );
});

test("rejects a component without a type, name or version/purl", () => {
  assert.throws(
    () => validateSbom(bom([{ name: "x", version: "1.0.0" }])),
    /component type is required/,
  );
  assert.throws(() => validateSbom(bom([{ type: "library" }])), /component name is required/);
  assert.throws(
    () => validateSbom(bom([{ type: "library", name: "x" }])),
    /version or purl is required/,
  );
});

test("rejects an empty license entry", () => {
  assert.throws(
    () => validateSbom(bom([component("x", "1.0.0", [{}])])),
    /license entry needs license or expression/,
  );
});

test("an invalid document carries every finding", () => {
  try {
    validateSbom({ components: [] });
    assert.fail("expected a validation error");
  } catch (error) {
    assert.ok(error instanceof SbomValidationError);
    assert.ok(error.messages.length >= 4);
  }
});

test("componentKey prefers the purl and falls back to type/name/version", () => {
  assert.equal(componentKey(component("alpha")), "pkg:npm/alpha@1.0.0");
  assert.equal(componentKey({ type: "library", name: "beta", version: "2" }), "library:beta@2");
});

test("merges three layers, de-duplicates and records layer counts", () => {
  const shared = component("shared");
  const merged = mergeSboms(
    {
      backend: bom([shared, component("backend-lib")]),
      frontend: bom([shared, component("frontend-lib")]),
      "ai-layer": bom([component("python-lib")]),
    },
    { version: "1.2.3", timestamp: "2026-01-01T00:00:00Z" },
  );
  assert.equal(merged.metadata.component.version, "1.2.3");
  assert.equal(merged.components.length, 4);
  assert.equal(merged.metadata.timestamp, "2026-01-01T00:00:00Z");
  assert.deepEqual(
    merged.metadata.properties
      .filter((property) => property.name.startsWith("awap:layer:"))
      .map((property) => `${property.name}=${property.value}`)
      .sort(),
    [
      "awap:layer:ai-layer:components=1",
      "awap:layer:backend:components=2",
      // `shared` was already contributed by the backend, so only frontend-lib is new.
      "awap:layer:frontend:components=1",
    ],
  );
  assert.equal(merged.specVersion, "1.6");
});

test("merge refuses a layer with an invalid SBOM", () => {
  assert.throws(
    () =>
      mergeSboms({
        backend: bom([component("a")]),
        frontend: bom([], {}),
        "ai-layer": bom([component("c")]),
      }),
    /Invalid CycloneDX SBOM/,
  );
});

test("licenseIds reads SPDX ids, names and expressions", () => {
  assert.deepEqual(licenseIds(component("a", "1", [{ license: { id: "MIT" } }])), ["MIT"]);
  assert.deepEqual(licenseIds(component("a", "1", [{ license: { name: "Custom" } }])), ["Custom"]);
  assert.deepEqual(licenseIds(component("a", "1", [{ expression: "MIT OR Apache-2.0" }])), [
    "MIT OR Apache-2.0",
  ]);
});

test("license policy passes allowed, reports missing and fails denied", () => {
  const document = bom([
    component("mit"),
    component("apache", "1.0.0", [{ license: { id: "Apache-2.0" } }]),
    component("unknown", "1.0.0", null),
  ]);
  const result = checkLicenses(document, POLICY);
  assert.equal(result.allowed, 2);
  assert.deepEqual(result.missing, ["unknown"]);
  assert.deepEqual(result.disallowed, []);

  const denied = checkLicenses(
    bom([component("gpl", "1.0.0", [{ license: { id: "GPL-3.0-only" } }])]),
    POLICY,
  );
  assert.deepEqual(denied.disallowed, [{ component: "gpl", licenses: ["GPL-3.0-only"] }]);
});

test("license expressions containing a denied identifier fail", () => {
  const result = checkLicenses(
    bom([component("dual", "1.0.0", [{ expression: "MIT OR GPL-3.0-only" }])]),
    POLICY,
  );
  assert.equal(result.disallowed.length, 1);
});

test("parseArguments reads the subcommand and options", () => {
  assert.deepEqual(parseArguments(["merge", "--backend", "b.json", "--output", "o.json"]), {
    command: "merge",
    options: { backend: "b.json", output: "o.json" },
  });
});

test("CLI validate, merge and licenses paths return the expected exit codes", async () => {
  const directory = await mkdtemp(join(tmpdir(), "awap-sbom-"));
  const backend = join(directory, "backend.json");
  const frontend = join(directory, "frontend.json");
  const ai = join(directory, "ai.json");
  const merged = join(directory, "merged.json");
  await writeFile(backend, JSON.stringify(bom([component("a")])));
  await writeFile(frontend, JSON.stringify(bom([component("b")])));
  await writeFile(ai, JSON.stringify(bom([component("c")])));

  assert.equal(await main(["validate", "--file", backend, "--layer", "backend"]), 0);
  assert.equal(
    await main(["merge", "--backend", backend, "--frontend", frontend, "--ai", ai, "--output", merged]),
    0,
  );
  const written = JSON.parse(await readFile(merged, "utf8"));
  assert.equal(written.components.length, 3);
  assert.equal(await main(["licenses", "--file", merged, "--policy", "scripts/sbom/license-policy.json"]), 0);
  assert.equal(await main(["unknown"]), 2);
});

test("CLI fails with a clear message for an invalid SBOM", async () => {
  const directory = await mkdtemp(join(tmpdir(), "awap-sbom-"));
  const invalid = join(directory, "invalid.json");
  await writeFile(invalid, JSON.stringify({ bomFormat: "SPDX" }));
  assert.equal(await main(["validate", "--file", invalid]), 1);
});

test("CLI fails the license policy for a denied component", async () => {
  const directory = await mkdtemp(join(tmpdir(), "awap-sbom-"));
  const document = join(directory, "denied.json");
  await writeFile(
    document,
    JSON.stringify(bom([component("gpl", "1.0.0", [{ license: { id: "GPL-3.0-only" } }])])),
  );
  assert.equal(await main(["licenses", "--file", document, "--policy", "scripts/sbom/license-policy.json"]), 1);
});
