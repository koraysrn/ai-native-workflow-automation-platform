import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { parse } from "yaml";

import {
  collectControllerEndpoints,
  compareEndpoints,
  extractControllerEndpoints,
  extractSpecEndpoints,
  isControllerContractPath,
} from "../openapi-controller-coverage.mjs";
import {
  breakingChanges,
  checkBreakingChange,
  compareSchemas,
  resolveSchema,
} from "../openapi-breaking-change.mjs";

const SPEC = new URL("../../docs/openapi/awap-openapi.yaml", import.meta.url);

function operation(overrides = {}) {
  return {
    get: {
      responses: { "200": { description: "ok" } },
      ...overrides,
    },
  };
}

test("extracts class and method mappings, including method-level RequestMapping", () => {
  const source = `
    @RestController
    @RequestMapping("/api/v1/things")
    class ThingController {
      @GetMapping
      Map list() { return null; }
      @PostMapping("/{id}/run")
      Map run() { return null; }
      @RequestMapping(value = "/{id}/replay", method = RequestMethod.POST)
      Map replay() { return null; }
    }
  `;
  const endpoints = extractControllerEndpoints(source);
  assert.deepEqual(
    endpoints.map((endpoint) => `${endpoint.method} ${endpoint.path}`),
    ["get /api/v1/things", "post /api/v1/things/{id}/run", "post /api/v1/things/{id}/replay"],
  );
});

test("defaults a bare mapping to the class path", () => {
  const endpoints = extractControllerEndpoints(`@RequestMapping("/api/v1/x")
    class XController { @GetMapping() Map get() { return null; } }`);
  assert.deepEqual(endpoints, [
    { method: "get", path: "/api/v1/x", controller: "XController" },
  ]);
});

test("reads every HTTP method from the OpenAPI paths object", () => {
  const endpoints = extractSpecEndpoints({
    paths: {
      "/a": { get: {}, post: {} },
      "/b": { delete: {} },
    },
  });
  assert.deepEqual([...endpoints].sort(), ["DELETE /b", "GET /a", "POST /a"]);
});

test("compareEndpoints reports missing and stale controller endpoints", () => {
  const comparison = compareEndpoints(
    [
      { method: "get", path: "/api/v1/a", controller: "A" },
      { method: "post", path: "/api/v1/b", controller: "B" },
    ],
    new Set(["GET /api/v1/a", "GET /api/v1/c"]),
  );
  assert.deepEqual(comparison.missingFromSpec, ["POST /api/v1/b"]);
  assert.deepEqual(comparison.staleInSpec, ["GET /api/v1/c"]);
});

test("actuator paths are documented but never treated as stale controllers", () => {
  assert.equal(isControllerContractPath("/api/v1/x"), true);
  assert.equal(isControllerContractPath("/actuator/health"), false);
  const comparison = compareEndpoints([], new Set(["GET /actuator/health", "GET /api/v1/ghost"]));
  assert.deepEqual(comparison.staleInSpec, ["GET /api/v1/ghost"]);
});

test("the real repository controller inventory matches the spec", async () => {
  const document = parse(await readFile(SPEC, "utf8"));
  const controllers = await collectControllerEndpoints();
  const comparison = compareEndpoints(controllers, extractSpecEndpoints(document));
  assert.deepEqual(comparison.missingFromSpec, []);
  assert.deepEqual(comparison.staleInSpec, []);
});

test("every documented error response uses the RFC 7807 problem+json media type", async () => {
  const document = parse(await readFile(SPEC, "utf8"));
  for (const [path, item] of Object.entries(document.paths)) {
    for (const method of ["get", "post", "put", "patch", "delete"]) {
      const operation = item[method];
      if (!operation) {
        continue;
      }
      for (const [status, response] of Object.entries(operation.responses ?? {})) {
        if (!/^[45]\d\d$/.test(status)) {
          continue;
        }
        const resolved = response.$ref ? resolveResponseRef(document, response.$ref) : response;
        assert.ok(
          resolved?.content?.["application/problem+json"],
          `${method.toUpperCase()} ${path} response ${status} is not RFC 7807 problem+json`,
        );
      }
    }
  }
});

test("every successful JSON response documents a schema", async () => {
  const document = parse(await readFile(SPEC, "utf8"));
  for (const [path, item] of Object.entries(document.paths)) {
    for (const method of ["get", "post", "put", "patch", "delete"]) {
      const operation = item[method];
      if (!operation) {
        continue;
      }
      for (const [status, response] of Object.entries(operation.responses ?? {})) {
        if (!/^2\d\d$/.test(status) || status === "204") {
          continue;
        }
        const resolved = response.$ref ? resolveResponseRef(document, response.$ref) : response;
        const content = resolved?.content ?? {};
        // SSE (text/event-stream) and empty bodies are valid non-JSON 2xx responses.
        if (!content["application/json"]) {
          continue;
        }
        assert.ok(
          content["application/json"].schema,
          `${method.toUpperCase()} ${path} response ${status} has no JSON schema`,
        );
      }
    }
  }
});

function resolveResponseRef(document, ref) {
  const segments = ref.replace(/^#\//, "").split("/");
  return segments.reduce((node, segment) => node?.[segment], document);
}

test("every documented path has a security scheme or explicitly opts out", async () => {
  const document = parse(await readFile(SPEC, "utf8"));
  assert.ok(document.components?.securitySchemes?.bearerAuth);
  for (const [path, item] of Object.entries(document.paths)) {
    for (const method of ["get", "post", "put", "patch", "delete"]) {
      if (!item[method]) {
        continue;
      }
      const hasGlobal = (document.security ?? []).length > 0;
      const hasOperation = Array.isArray(item[method].security);
      assert.ok(
        hasGlobal || hasOperation,
        `${method.toUpperCase()} ${path} has no security scheme or explicit opt-out`,
      );
    }
  }
});

function minimalDocument(paths = {}) {
  return { openapi: "3.0.3", paths };
}

test("an unchanged document has no breaking changes", () => {
  const doc = minimalDocument({
    "/api/v1/a": {
      get: {
        responses: {
          "200": {
            content: {
              "application/json": {
                schema: { type: "object", properties: { id: { type: "string" } } },
              },
            },
          },
        },
      },
    },
  });
  assert.deepEqual(breakingChanges(doc, structuredClone(doc)), []);
});

test("removed paths, methods and status codes are breaking", () => {
  const base = minimalDocument({
    "/api/v1/a": { get: {}, post: {} },
    "/api/v1/b": { get: { responses: { "200": {}, "404": {} } } },
  });
  const current = minimalDocument({
    "/api/v1/a": { get: {} },
    "/api/v1/b": { get: { responses: { "200": {} } } },
  });
  const findings = breakingChanges(base, current);
  assert.ok(findings.some((finding) => finding.includes("POST /api/v1/a: operation removed")));
  assert.ok(findings.some((finding) => finding.includes("GET /api/v1/b: response 404 removed")));
  assert.ok(findings.some((finding) => finding.includes("path removed: /api/v1/b") === false));
});

test("a new required request property is breaking; a new optional one is not", () => {
  const base = minimalDocument({
    "/api/v1/a": {
      post: {
        requestBody: {
          content: { "application/json": { schema: { type: "object", properties: {} } } },
        },
        responses: {},
      },
    },
  });
  const withRequired = structuredClone(base);
  withRequired.paths["/api/v1/a"].post.requestBody.content["application/json"].schema = {
    type: "object",
    required: ["name"],
    properties: { name: { type: "string" } },
  };
  assert.ok(
    breakingChanges(base, withRequired).some((finding) => finding.includes("new required request")),
  );

  const optional = structuredClone(withRequired);
  delete optional.paths["/api/v1/a"].post.requestBody.content["application/json"].schema.required;
  assert.deepEqual(breakingChanges(base, optional), []);
});

test("removed response properties, type changes and enum removals are breaking", () => {
  const base = minimalDocument({
    "/api/v1/a": {
      get: {
        responses: {
          "200": {
            content: {
              "application/json": {
                schema: {
                  type: "object",
                  required: ["id", "role"],
                  properties: {
                    id: { type: "string" },
                    role: { type: "string", enum: ["ADMIN", "USER"] },
                  },
                },
              },
            },
          },
        },
      },
    },
  });
  const current = structuredClone(base);
  const schema =
    current.paths["/api/v1/a"].get.responses["200"].content["application/json"].schema;
  schema.properties = { role: { type: "number", enum: ["ADMIN"] } };
  schema.required = ["role"];
  const findings = breakingChanges(base, current);
  assert.ok(findings.some((finding) => finding.includes("id: response property removed")));
  assert.ok(findings.some((finding) => finding.includes("type changed from string to number")));
  assert.ok(findings.some((finding) => finding.includes("enum value(s) removed: USER")));
});

test("compareSchemas resolves local component references", () => {
  const base = { components: { schemas: { Item: { type: "object", properties: { id: { type: "string" } } } } } };
  const current = { components: { schemas: { Item: { type: "object", properties: { id: { type: "number" } } } } } };
  assert.equal(resolveSchema(base, { $ref: "#/components/schemas/Item" }).type, "object");
  const findings = compareSchemas(
    base,
    current,
    { $ref: "#/components/schemas/Item" },
    { $ref: "#/components/schemas/Item" },
    "response",
    "item",
  );
  assert.deepEqual(findings, ["item.id: type changed from string to number"]);
});

test("checkBreakingChange reads files and returns findings", async () => {
  assert.deepEqual(
    await checkBreakingChange(fileURLToPath(SPEC), fileURLToPath(SPEC)),
    [],
  );
});
