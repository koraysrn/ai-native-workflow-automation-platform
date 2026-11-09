#!/usr/bin/env node
/**
 * HPA scale-out/scale-in proof (issue #187).
 *
 * `--dry-run` validates that every HPA has a CPU target and scale behavior.
 * `--cluster` creates a busybox load pod against the backend and polls the
 * backend HPA until it scales out, then removes the load and polls until it
 * scales back in. It requires a cluster with metrics-server.
 */

import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { parseAllDocuments } from "yaml";

export function desiredReplicas({ current, currentCpu, targetCpu, min, max }) {
  if (targetCpu <= 0) {
    throw new Error("targetCpu must be greater than 0");
  }
  if (current <= 0) {
    return min;
  }
  const desired = Math.ceil(current * (currentCpu / targetCpu));
  return Math.min(max, Math.max(min, desired));
}

export function scaleDirection(from, to) {
  if (to > from) {
    return "out";
  }
  if (to < from) {
    return "in";
  }
  return "steady";
}

/** Simulates the prod backend HPA for a CPU utilization value. */
export function hpaPlan(cpuPercent) {
  const desired = desiredReplicas({
    current: 3,
    currentCpu: cpuPercent,
    targetCpu: 70,
    min: 3,
    max: 12,
  });
  return { cpuPercent, desired, direction: scaleDirection(3, desired) };
}

export function readHpaManifests(text) {
  return parseAllDocuments(text)
    .map((document) => document.toJS())
    .filter((document) => document?.kind === "HorizontalPodAutoscaler");
}

/** Returns validation errors for the HPA manifests used by the load test. */
export function validateHpaManifests(hpas) {
  const errors = [];
  for (const hpa of hpas) {
    const name = hpa.metadata?.name ?? "unknown";
    const target = hpa.spec?.metrics?.[0]?.resource?.target?.averageUtilization;
    if (target !== 70) {
      errors.push(`${name} must scale on 70% CPU utilization (found ${target}).`);
    }
    if ((hpa.spec?.behavior?.scaleUp?.policies?.length ?? 0) === 0) {
      errors.push(`${name} is missing scaleUp behavior policies.`);
    }
    if ((hpa.spec?.behavior?.scaleDown?.policies?.length ?? 0) === 0) {
      errors.push(`${name} is missing scaleDown behavior policies.`);
    }
  }
  return errors;
}

/** Polls an HPA until the predicate matches or the attempt budget is exhausted. */
export async function waitForReplicas({
  exec,
  sleep,
  namespace,
  name,
  predicate,
  attempts = 36,
}) {
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    const result = exec("kubectl", [
      "-n",
      namespace,
      "get",
      "hpa",
      name,
      "-o",
      "json",
    ]);
    if (result.status === 0) {
      try {
        const hpa = JSON.parse(result.stdout);
        const replicas = hpa.status?.currentReplicas ?? 0;
        if (predicate(replicas)) {
          return replicas;
        }
      } catch {
        // Transient kubectl output; keep polling.
      }
    }
    if (attempt < attempts) {
      await sleep(5000);
    }
  }
  return undefined;
}

export async function runCluster({
  exec,
  sleep,
  log = console.log,
  namespace = "awap-dev",
  minReplicas = 1,
  scaleOutTarget = 2,
  attempts = 36,
}) {
  const created = exec("kubectl", [
    "-n",
    namespace,
    "run",
    "awap-hpa-load",
    "--image=busybox:1.36",
    "--restart=Never",
    "--command",
    "--",
    "/bin/sh",
    "-c",
    "while true; do wget -q -O- http://awap-backend:8080/actuator/health >/dev/null; done",
  ]);
  if (created.status !== 0) {
    log(`FAIL: could not create the load pod: ${created.stderr || created.stdout}`);
    return false;
  }

  const scaledOut = await waitForReplicas({
    exec,
    sleep,
    namespace,
    name: "awap-backend",
    predicate: (replicas) => replicas >= scaleOutTarget,
    attempts,
  });
  log(
    scaledOut === undefined
      ? "FAIL: the backend HPA did not scale out."
      : `OK: backend scaled out to ${scaledOut} replicas.`,
  );

  exec("kubectl", ["-n", namespace, "delete", "pod", "awap-hpa-load", "--ignore-not-found"]);

  const scaledIn = await waitForReplicas({
    exec,
    sleep,
    namespace,
    name: "awap-backend",
    predicate: (replicas) => replicas === minReplicas,
    attempts,
  });
  log(
    scaledIn === undefined
      ? "FAIL: the backend HPA did not scale back in."
      : `OK: backend scaled back in to ${scaledIn} replica(s).`,
  );

  return scaledOut !== undefined && scaledIn !== undefined;
}

export async function main(
  argv = process.argv.slice(2),
  cwd = process.cwd(),
  exec = (command, args) => spawnSync(command, args, { cwd, encoding: "utf8" }),
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  log = console.log,
) {
  if (argv.includes("--dry-run")) {
    const text = readFileSync(join(cwd, "infra/k8s/base/hpa.yaml"), "utf8");
    const errors = validateHpaManifests(readHpaManifests(text));
    if (errors.length > 0) {
      for (const error of errors) {
        log(`FAIL: ${error}`);
      }
      return 1;
    }
    log("OK: every HPA has a 70% CPU target and scale behavior.");
    for (const cpu of [50, 70, 90, 140]) {
      const plan = hpaPlan(cpu);
      log(`CPU ${cpu}% -> ${plan.desired} replicas (${plan.direction})`);
    }
    return 0;
  }

  if (argv.includes("--cluster")) {
    const namespaceIndex = argv.indexOf("--namespace");
    const namespace = namespaceIndex >= 0 ? argv[namespaceIndex + 1] : "awap-dev";
    const ok = await runCluster({ exec, sleep, log, namespace });
    return ok ? 0 : 1;
  }

  log("Usage: node load-test/hpa-scale.mjs --dry-run | --cluster [--namespace awap-dev]");
  return 2;
}

const invokedDirectly =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedDirectly) {
  process.exitCode = await main();
}
