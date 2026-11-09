import assert from "node:assert/strict";
import test from "node:test";
import { stringify } from "yaml";

import {
  collectSecretProviders,
  collectSecretRefs,
  main,
  validateSecretKeys,
} from "../check-secret-keys.mjs";

function docs() {
  return [
    {
      apiVersion: "apps/v1",
      kind: "Deployment",
      metadata: { name: "awap-backend" },
      spec: {
        template: {
          spec: {
            containers: [
              {
                name: "backend",
                env: [
                  {
                    name: "DB_URL",
                    valueFrom: {
                      secretKeyRef: { name: "awap-backend-secrets", key: "db-url" },
                    },
                  },
                  {
                    name: "JWT_SECRET",
                    valueFrom: {
                      secretKeyRef: { name: "awap-backend-secrets", key: "jwt-secret" },
                    },
                  },
                ],
              },
            ],
          },
        },
      },
    },
    {
      apiVersion: "external-secrets.io/v1beta1",
      kind: "ExternalSecret",
      metadata: { name: "awap-backend-secrets" },
      spec: {
        target: { name: "awap-backend-secrets" },
        data: [
          { secretKey: "db-url", remoteRef: { key: "awap-dev/backend", property: "db-url" } },
          { secretKey: "jwt-secret", remoteRef: { key: "awap-dev/backend", property: "jwt-secret" } },
        ],
      },
    },
  ];
}

test("collects secret refs and providers", () => {
  const refs = collectSecretRefs(docs());
  assert.deepEqual([...refs].sort(), [
    "awap-backend-secrets/db-url",
    "awap-backend-secrets/jwt-secret",
  ]);
  assert.deepEqual([...collectSecretProviders(docs()).get("awap-backend-secrets")], [
    "db-url",
    "jwt-secret",
  ]);
});

test("complete references pass", () => {
  assert.deepEqual(validateSecretKeys(docs()), []);
});

test("a missing key fails the preflight", () => {
  const input = docs();
  input[1].spec.data.pop();
  const errors = validateSecretKeys(input);
  assert.ok(errors.some((error) => error.includes("does not provide the referenced key jwt-secret")));
});

test("dataFrom is rejected because keys cannot be verified", () => {
  const input = docs();
  input[1].spec.data = [];
  input[1].spec.dataFrom = [{ extract: { key: "awap-dev/backend" } }];
  const errors = validateSecretKeys(input);
  assert.ok(errors.some((error) => error.includes("uses dataFrom")));
});

test("main passes for a complete render and fails for a missing key", () => {
  const renderFor = (env) => {
    const input = docs();
    input[1].spec.data[0].remoteRef.key = `awap-${env}/backend`;
    input[1].spec.data[1].remoteRef.key = `awap-${env}/backend`;
    return input.map((doc) => stringify(doc)).join("---\n");
  };
  const execOk = (command, args) => {
    const env = args[1].split("/").pop();
    return { status: 0, stdout: renderFor(env), stderr: "" };
  };
  assert.equal(main([], process.cwd(), execOk, () => {}), 0);

  const execMissing = (command, args) => {
    const env = args[1].split("/").pop();
    const input = docs();
    input[1].spec.data.pop();
    input[1].spec.data[0].remoteRef.key = `awap-${env}/backend`;
    return { status: 0, stdout: input.map((doc) => stringify(doc)).join("---\n"), stderr: "" };
  };
  assert.equal(main([], process.cwd(), execMissing, () => {}), 1);
});
