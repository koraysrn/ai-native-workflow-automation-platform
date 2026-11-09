import assert from "node:assert/strict";
import test from "node:test";
import { stringify } from "yaml";

import {
  applicationSetElements,
  ENVIRONMENTS,
  main,
  OVERLAY_EXPECTATIONS,
  parseDocuments,
  validateOverlay,
} from "../validate-overlays.mjs";

function render(docs) {
  return docs.map((doc) => stringify(doc)).join("---\n");
}

function docsForEnv(env) {
  const expectation = OVERLAY_EXPECTATIONS[env];
  const docs = [
    { apiVersion: "v1", kind: "Namespace", metadata: { name: "awap-dev" } },
    {
      apiVersion: "apps/v1",
      kind: "Deployment",
      metadata: { name: "awap-backend", namespace: "awap-dev" },
      spec: {
        replicas: 1,
        template: {
          spec: { containers: [{ name: "backend", image: "ghcr.io/awap/awap-backend:dev" }] },
        },
      },
    },
    {
      apiVersion: "apps/v1",
      kind: "Deployment",
      metadata: { name: "awap-frontend", namespace: "awap-dev" },
      spec: {
        replicas: 1,
        template: {
          spec: { containers: [{ name: "frontend", image: "ghcr.io/awap/awap-frontend:dev" }] },
        },
      },
    },
    {
      apiVersion: "apps/v1",
      kind: "Deployment",
      metadata: { name: "awap-ai-layer", namespace: "awap-dev" },
      spec: {
        replicas: 1,
        template: {
          spec: { containers: [{ name: "ai-layer", image: "ghcr.io/awap/awap-ai-layer:dev" }] },
        },
      },
    },
    {
      apiVersion: "networking.k8s.io/v1",
      kind: "Ingress",
      metadata: { name: "awap-ingress", namespace: "awap-dev" },
      spec: {
        rules: [{ host: "dev.awap.local" }],
        tls: [{ hosts: ["dev.awap.local"], secretName: "awap-dev-tls" }],
      },
    },
    {
      apiVersion: "autoscaling/v2",
      kind: "HorizontalPodAutoscaler",
      metadata: { name: "awap-backend", namespace: "awap-dev" },
      spec: { minReplicas: 1, maxReplicas: 3 },
    },
    {
      apiVersion: "autoscaling/v2",
      kind: "HorizontalPodAutoscaler",
      metadata: { name: "awap-frontend", namespace: "awap-dev" },
      spec: { minReplicas: 1, maxReplicas: 3 },
    },
    {
      apiVersion: "autoscaling/v2",
      kind: "HorizontalPodAutoscaler",
      metadata: { name: "awap-ai-layer", namespace: "awap-dev" },
      spec: { minReplicas: 1, maxReplicas: 2 },
    },
    {
      apiVersion: "v1",
      kind: "ConfigMap",
      metadata: { name: "awap-feature-flags", namespace: "awap-dev" },
      data: { ENVIRONMENT: "dev", FEATURE_AI_STEPS: "false" },
    },
    {
      apiVersion: "bitnami.com/v1alpha1",
      kind: "SealedSecret",
      metadata: { name: "awap-backend-secrets", namespace: "awap-dev" },
      spec: { template: { metadata: { namespace: "awap-dev" } } },
    },
  ];
  for (const doc of docs) {
    if (doc.kind !== "Namespace" && doc.metadata?.namespace) {
      doc.metadata.namespace = expectation.namespace;
    }
  }
  docs[0].metadata.name = expectation.namespace;
  docs[1].spec.replicas = expectation.replicas.backend;
  docs[1].spec.template.spec.containers[0].image = `ghcr.io/awap/awap-backend:${env}`;
  docs[2].spec.replicas = expectation.replicas.frontend;
  docs[2].spec.template.spec.containers[0].image = `ghcr.io/awap/awap-frontend:${env}`;
  docs[3].spec.replicas = expectation.replicas.ai;
  docs[3].spec.template.spec.containers[0].image = `ghcr.io/awap/awap-ai-layer:${env}`;
  docs[4].spec.rules[0].host = expectation.host;
  docs[4].spec.tls[0] = { hosts: [expectation.host], secretName: expectation.tlsSecret };
  for (const [index, key] of [
    [5, "backend"],
    [6, "frontend"],
    [7, "ai"],
  ]) {
    docs[index].spec.minReplicas = expectation.hpa[key][0];
    docs[index].spec.maxReplicas = expectation.hpa[key][1];
  }
  docs[8].data.ENVIRONMENT = env;
  docs[8].data.FEATURE_AI_STEPS = expectation.featureAi;
  docs[9].spec.template.metadata.namespace = expectation.namespace;
  return docs;
}

function devDocs() {
  return docsForEnv("dev");
}

test("parseDocuments returns every YAML document", () => {
  assert.equal(parseDocuments(render(devDocs())).length, devDocs().length);
});

test("dev overlay fixture passes validation", () => {
  const result = validateOverlay("dev", render(devDocs()));
  assert.deepEqual(result.errors, []);
});

test("a production host in the dev overlay is rejected", () => {
  const docs = devDocs();
  docs[4].spec.rules[0].host = "awap.example.com";
  const result = validateOverlay("dev", render(docs));
  assert.ok(result.errors.some((error) => error.includes("ingress host")));
});

test("wrong replicas, image tags and HPA bounds are rejected", () => {
  const docs = devDocs();
  docs[1].spec.replicas = 5;
  docs[2].spec.template.spec.containers[0].image = "ghcr.io/awap/awap-frontend:prod";
  docs[5].spec.maxReplicas = 99;
  const result = validateOverlay("dev", render(docs));
  assert.ok(result.errors.some((error) => error.includes("awap-backend replicas")));
  assert.ok(result.errors.some((error) => error.includes("awap-frontend image")));
  assert.ok(result.errors.some((error) => error.includes("awap-backend HPA")));
});

test("a resource in the wrong namespace is rejected", () => {
  const docs = devDocs();
  docs[3].metadata.namespace = "awap-prod";
  const result = validateOverlay("dev", render(docs));
  assert.ok(result.errors.some((error) => error.includes("namespace awap-prod")));
});

test("the ApplicationSet covers every environment with its own path", () => {
  const elements = applicationSetElements();
  assert.deepEqual(
    elements.map((element) => element.env),
    ENVIRONMENTS,
  );
  assert.deepEqual(
    elements.map((element) => element.path),
    ENVIRONMENTS.map((env) => `infra/k8s/overlays/${env}`),
  );
  assert.deepEqual(
    elements.map((element) => element.namespace),
    ["awap-dev", "awap-staging", "awap-prod"],
  );
});

test("main fails when kustomize cannot render or kubeconform fails", () => {
  const renderFailure = () => ({ status: 1, stdout: "", stderr: "boom" });
  assert.equal(main([], process.cwd(), renderFailure, () => {}), 1);

  const renderFor = (command, args) => {
    if (command === "kubeconform") {
      return { status: 1, stdout: "", stderr: "invalid" };
    }
    const env = args[1].split("/").pop();
    return { status: 0, stdout: render(docsForEnv(env)), stderr: "" };
  };
  assert.equal(main(["--kubeconform"], process.cwd(), renderFor, () => {}), 1);
});

test("main passes when every overlay renders and validates", () => {
  const exec = (command, args) => {
    if (command === "kubeconform") {
      return { status: 0, stdout: "", stderr: "" };
    }
    const env = args[1].split("/").pop();
    return { status: 0, stdout: render(docsForEnv(env)), stderr: "" };
  };
  assert.equal(main(["--kubeconform"], process.cwd(), exec, () => {}), 0);
});

test("kind mode creates the namespace before the server dry run", () => {
  const calls = [];
  const exec = (command, args) => {
    calls.push(args);
    if (command === "kubectl" && args[0] === "apply") {
      return { status: 0, stdout: "", stderr: "" };
    }
    const env = args[1].split("/").pop();
    return { status: 0, stdout: render(docsForEnv(env)), stderr: "" };
  };
  assert.equal(main(["--kind"], process.cwd(), exec, () => {}), 0);
  assert.ok(calls.some((args) => args[0] === "apply" && args[1] === "-f"));
  assert.ok(calls.some((args) => args[0] === "apply" && args[1] === "--dry-run=server"));
});

test("a failed kind namespace creation fails the run", () => {
  const exec = (command, args) => {
    if (command === "kubectl" && args[0] === "apply" && args[1] === "-f") {
      return { status: 1, stdout: "", stderr: "forbidden" };
    }
    if (command === "kubectl" && args[0] === "apply") {
      return { status: 0, stdout: "", stderr: "" };
    }
    const env = args[1].split("/").pop();
    return { status: 0, stdout: render(docsForEnv(env)), stderr: "" };
  };
  assert.equal(main(["--kind"], process.cwd(), exec, () => {}), 1);
});
