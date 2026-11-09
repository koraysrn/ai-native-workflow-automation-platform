#!/usr/bin/env node
/**
 * Pre-deploy secret-key preflight (issue #188).
 *
 * Renders every overlay and proves that every `secretKeyRef` in a workload is
 * produced by an ExternalSecret `data` entry. `dataFrom` is rejected because it
 * hides the key names from the preflight. This catches a missing Vault key
 * before the pod reaches CreateContainerConfigError.
 */

import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";
import { ENVIRONMENTS, parseDocuments } from "./validate-overlays.mjs";

const WORKLOAD_KINDS = new Set(["Deployment", "StatefulSet", "DaemonSet", "Job", "CronJob"]);

function podSpecs(doc) {
  if (doc.kind === "CronJob") {
    return [doc.spec?.jobTemplate?.spec?.template?.spec].filter(Boolean);
  }
  if (WORKLOAD_KINDS.has(doc.kind)) {
    return [doc.spec?.template?.spec].filter(Boolean);
  }
  return [];
}

/** Returns the set of `secretName/key` references used by workloads. */
export function collectSecretRefs(docs) {
  const refs = new Set();
  for (const doc of docs) {
    for (const podSpec of podSpecs(doc)) {
      for (const container of [
        ...(podSpec.containers ?? []),
        ...(podSpec.initContainers ?? []),
      ]) {
        for (const env of container.env ?? []) {
          const ref = env.valueFrom?.secretKeyRef;
          if (ref?.name && ref?.key) {
            refs.add(`${ref.name}/${ref.key}`);
          }
        }
      }
    }
  }
  return refs;
}

/** Returns `Map<secretName, Set<key>>` from explicit ExternalSecret data. */
export function collectSecretProviders(docs) {
  const providers = new Map();
  for (const doc of docs) {
    if (doc.kind !== "ExternalSecret") {
      continue;
    }
    const target = doc.spec?.target?.name ?? doc.metadata?.name;
    if (!target) {
      continue;
    }
    const keys = providers.get(target) ?? new Set();
    for (const entry of doc.spec?.data ?? []) {
      if (entry.secretKey) {
        keys.add(entry.secretKey);
      }
    }
    providers.set(target, keys);
  }
  return providers;
}

/** Returns human-readable errors for missing keys and opaque dataFrom usage. */
export function validateSecretKeys(docs) {
  const errors = [];
  for (const doc of docs) {
    if (doc.kind === "ExternalSecret" && (doc.spec?.dataFrom?.length ?? 0) > 0) {
      errors.push(
        `ExternalSecret/${doc.metadata?.name} uses dataFrom; use explicit data entries so the preflight can verify every key.`,
      );
    }
  }

  const providers = collectSecretProviders(docs);
  for (const reference of collectSecretRefs(docs)) {
    const [name, key] = reference.split("/");
    if (!providers.get(name)?.has(key)) {
      errors.push(`secret ${name} does not provide the referenced key ${key}`);
    }
  }
  return errors;
}

export function main(
  argv = process.argv.slice(2),
  cwd = process.cwd(),
  exec = (command, args) => spawnSync(command, args, { cwd, encoding: "utf8" }),
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
    const errors = validateSecretKeys(parseDocuments(result.stdout));
    if (errors.length > 0) {
      for (const error of errors) {
        log(`FAIL: ${env}: ${error}`);
      }
      failed = true;
    } else {
      log(`OK: ${env} workloads reference only keys provided by ExternalSecrets.`);
    }
  }
  return failed ? 1 : 0;
}

const invokedDirectly =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedDirectly) {
  process.exitCode = main();
}
