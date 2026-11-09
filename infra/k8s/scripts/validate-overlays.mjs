#!/usr/bin/env node
/**
 * Base/overlay validation for the Kubernetes manifests (issue #186).
 *
 * - renders every overlay with `kubectl kustomize`;
 * - asserts the environment namespace, host, TLS secret, replicas, HPA bounds,
 *   feature flags and image tags are the expected ones;
 * - optionally validates with kubeconform (`--kubeconform`);
 * - optionally applies the non-secret documents to a kind cluster with a
 *   server-side dry run (`--kind`).
 */

import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";
import { parseAllDocuments, stringify } from "yaml";

export const OVERLAY_EXPECTATIONS = {
  dev: {
    namespace: "awap-dev",
    host: "dev.awap.local",
    tlsSecret: "awap-dev-tls",
    replicas: { backend: 1, frontend: 1, ai: 1 },
    hpa: { backend: [1, 3], frontend: [1, 3], ai: [1, 2] },
    featureAi: "false",
  },
  staging: {
    namespace: "awap-staging",
    host: "staging.awap.local",
    tlsSecret: "awap-staging-tls",
    replicas: { backend: 2, frontend: 2, ai: 1 },
    hpa: { backend: [2, 6], frontend: [2, 4], ai: [1, 3] },
    featureAi: "true",
  },
  prod: {
    namespace: "awap-prod",
    host: "awap.example.com",
    tlsSecret: "awap-prod-tls",
    replicas: { backend: 3, frontend: 3, ai: 2 },
    hpa: { backend: [3, 12], frontend: [3, 8], ai: [2, 6] },
    featureAi: "true",
  },
};

export const ENVIRONMENTS = Object.keys(OVERLAY_EXPECTATIONS);

export function expectedOverlay(env) {
  const expectation = OVERLAY_EXPECTATIONS[env];
  if (!expectation) {
    throw new Error(`Unknown environment: ${env}`);
  }
  return expectation;
}

/** Parses a multi-document YAML stream. */
export function parseDocuments(text) {
  return parseAllDocuments(text)
    .map((document) => document.toJS())
    .filter((document) => document && typeof document === "object");
}

export function findByKindAndName(docs, kind, name) {
  return docs.find((doc) => doc.kind === kind && doc.metadata?.name === name);
}

/** Returns the generated Argo CD ApplicationSet list elements. */
export function applicationSetElements() {
  return ENVIRONMENTS.map((env) => ({
    env,
    namespace: expectedOverlay(env).namespace,
    path: `infra/k8s/overlays/${env}`,
  }));
}

/** Validates a rendered overlay; returns an array of human-readable errors. */
export function validateOverlay(env, text) {
  const expectation = expectedOverlay(env);
  const docs = parseDocuments(text);
  const errors = [];
  const check = (condition, message) => {
    if (!condition) {
      errors.push(message);
    }
  };

  check(
    findByKindAndName(docs, "Namespace", expectation.namespace),
    `namespace ${expectation.namespace} is missing`,
  );

  for (const doc of docs) {
    if (doc.kind === "Namespace") {
      continue;
    }
    check(
      doc.metadata?.namespace === expectation.namespace,
      `${doc.kind}/${doc.metadata?.name} has namespace ${doc.metadata?.namespace}, expected ${expectation.namespace}`,
    );
  }

  const ingress = findByKindAndName(docs, "Ingress", "awap-ingress");
  check(
    ingress?.spec?.rules?.[0]?.host === expectation.host,
    `ingress host is ${ingress?.spec?.rules?.[0]?.host}, expected ${expectation.host}`,
  );
  check(
    ingress?.spec?.tls?.[0]?.secretName === expectation.tlsSecret,
    `ingress TLS secret is ${ingress?.spec?.tls?.[0]?.secretName}, expected ${expectation.tlsSecret}`,
  );

  const deployExpectations = {
    "awap-backend": ["backend", "ghcr.io/awap/awap-backend"],
    "awap-frontend": ["frontend", "ghcr.io/awap/awap-frontend"],
    "awap-ai-layer": ["ai", "ghcr.io/awap/awap-ai-layer"],
  };
  for (const [name, [key, image]] of Object.entries(deployExpectations)) {
    const deployment = findByKindAndName(docs, "Deployment", name);
    check(
      deployment?.spec?.replicas === expectation.replicas[key],
      `${name} replicas is ${deployment?.spec?.replicas}, expected ${expectation.replicas[key]}`,
    );
    const renderedImage = deployment?.spec?.template?.spec?.containers?.[0]?.image;
    check(
      renderedImage === `${image}:${env}`,
      `${name} image is ${renderedImage}, expected ${image}:${env}`,
    );
  }

  const hpaExpectations = {
    "awap-backend": "backend",
    "awap-frontend": "frontend",
    "awap-ai-layer": "ai",
  };
  for (const [name, key] of Object.entries(hpaExpectations)) {
    const hpa = findByKindAndName(docs, "HorizontalPodAutoscaler", name);
    const [min, max] = expectation.hpa[key];
    check(
      hpa?.spec?.minReplicas === min && hpa?.spec?.maxReplicas === max,
      `${name} HPA is ${hpa?.spec?.minReplicas}-${hpa?.spec?.maxReplicas}, expected ${min}-${max}`,
    );
  }

  const flags = docs.find(
    (doc) => doc.kind === "ConfigMap" && doc.metadata?.name?.startsWith("awap-feature-flags"),
  );
  check(
    flags?.data?.ENVIRONMENT === env,
    `feature flag ENVIRONMENT is ${flags?.data?.ENVIRONMENT}, expected ${env}`,
  );
  check(
    flags?.data?.FEATURE_AI_STEPS === expectation.featureAi,
    `feature flag FEATURE_AI_STEPS is ${flags?.data?.FEATURE_AI_STEPS}, expected ${expectation.featureAi}`,
  );

  const sealed = findByKindAndName(docs, "SealedSecret", "awap-backend-secrets");
  check(
    sealed?.spec?.template?.metadata?.namespace === expectation.namespace,
    `SealedSecret target namespace is ${sealed?.spec?.template?.metadata?.namespace}, expected ${expectation.namespace}`,
  );

  return { errors, docs };
}

function run(command, args, options) {
  return spawnSync(command, args, { encoding: "utf8", ...options });
}

export function main(
  argv = process.argv.slice(2),
  cwd = process.cwd(),
  exec = run,
  log = console.log,
) {
  let failed = false;
  for (const env of ENVIRONMENTS) {
    const result = exec("kubectl", ["kustomize", `infra/k8s/overlays/${env}`], { cwd });
    if (result.status !== 0) {
      log(`FAIL: kustomize build ${env}: ${result.stderr || result.stdout}`);
      failed = true;
      continue;
    }
    const { errors, docs } = validateOverlay(env, result.stdout);
    if (errors.length > 0) {
      for (const error of errors) {
        log(`FAIL: ${env}: ${error}`);
      }
      failed = true;
      continue;
    }
    log(`OK: ${env} overlay renders with the expected namespace, host, replicas, HPA and flags.`);

    if (argv.includes("--kubeconform")) {
      const conform = exec(
        "kubeconform",
        ["-strict", "-summary", "-ignore-missing-schemas", "-"],
        {
          cwd,
          input: result.stdout,
        },
      );
      if (conform.status !== 0) {
        log(`FAIL: kubeconform ${env}: ${conform.stderr || conform.stdout}`);
        failed = true;
      } else {
        log(`OK: ${env} overlay passes kubeconform.`);
      }
    }

    if (argv.includes("--kind")) {
      // A server-side dry run validates against existing objects; create the
      // overlay namespace first (the kind cluster is throwaway).
      const namespaceDoc = docs.find((doc) => doc.kind === "Namespace");
      if (namespaceDoc) {
        const created = exec("kubectl", ["apply", "-f", "-"], {
          cwd,
          input: stringify(namespaceDoc),
        });
        if (created.status !== 0) {
          log(`FAIL: kind namespace ${env}: ${created.stderr || created.stdout}`);
          failed = true;
          continue;
        }
      }
      const nonSecret = docs
        .filter((doc) => doc.kind !== "SealedSecret")
        .map((doc) => stringify(doc))
        .join("---\n");
      const apply = exec("kubectl", ["apply", "--dry-run=server", "-f", "-"], {
        cwd,
        input: nonSecret,
      });
      if (apply.status !== 0) {
        log(`FAIL: kind dry-run ${env}: ${apply.stderr || apply.stdout}`);
        failed = true;
      } else {
        log(`OK: ${env} overlay is accepted by the kind cluster (server dry-run).`);
      }
    }
  }
  return failed ? 1 : 0;
}

const invokedDirectly =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedDirectly) {
  process.exitCode = main();
}
