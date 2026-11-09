import assert from "node:assert/strict";
import test from "node:test";

import {
  desiredReplicas,
  hpaPlan,
  main,
  readHpaManifests,
  runCluster,
  scaleDirection,
  validateHpaManifests,
  waitForReplicas,
} from "../hpa-scale.mjs";

const HPA = `apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: awap-backend
spec:
  minReplicas: 3
  maxReplicas: 12
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
  behavior:
    scaleUp:
      policies:
        - type: Pods
          value: 2
          periodSeconds: 30
    scaleDown:
      policies:
        - type: Percent
          value: 50
          periodSeconds: 60
`;

test("desiredReplicas scales up, down and clamps to bounds", () => {
  assert.equal(
    desiredReplicas({ current: 3, currentCpu: 140, targetCpu: 70, min: 3, max: 12 }),
    6,
  );
  assert.equal(
    desiredReplicas({ current: 6, currentCpu: 35, targetCpu: 70, min: 3, max: 12 }),
    3,
  );
  assert.equal(
    desiredReplicas({ current: 10, currentCpu: 700, targetCpu: 70, min: 3, max: 12 }),
    12,
  );
  assert.equal(
    desiredReplicas({ current: 0, currentCpu: 0, targetCpu: 70, min: 3, max: 12 }),
    3,
  );
  assert.throws(
    () => desiredReplicas({ current: 1, currentCpu: 1, targetCpu: 0, min: 1, max: 2 }),
    /greater than 0/,
  );
});

test("scaleDirection reports out, in and steady", () => {
  assert.equal(scaleDirection(3, 6), "out");
  assert.equal(scaleDirection(6, 3), "in");
  assert.equal(scaleDirection(3, 3), "steady");
  assert.deepEqual(hpaPlan(140), { cpuPercent: 140, desired: 6, direction: "out" });
});

test("validateHpaManifests accepts the shipped shape and rejects gaps", () => {
  const [hpa] = readHpaManifests(HPA);
  assert.deepEqual(validateHpaManifests([hpa]), []);
  hpa.spec.metrics[0].resource.target.averageUtilization = 50;
  hpa.spec.behavior = {};
  const errors = validateHpaManifests([hpa]);
  assert.equal(errors.length, 3);
});

test("waitForReplicas returns on a match and times out otherwise", async () => {
  const exec = () => ({
    status: 0,
    stdout: JSON.stringify({ status: { currentReplicas: 4 } }),
    stderr: "",
  });
  assert.equal(
    await waitForReplicas({
      exec,
      sleep: async () => {},
      namespace: "awap-dev",
      name: "awap-backend",
      predicate: (replicas) => replicas === 4,
    }),
    4,
  );
  assert.equal(
    await waitForReplicas({
      exec,
      sleep: async () => {},
      namespace: "awap-dev",
      name: "awap-backend",
      predicate: (replicas) => replicas === 99,
      attempts: 2,
    }),
    undefined,
  );
});

test("runCluster proves scale-out then scale-in", async () => {
  const replicas = [1, 2, 3, 3, 2, 1];
  const calls = [];
  const exec = (command, args) => {
    calls.push(args);
    if (args[0] === "run" || args[0] === "delete") {
      return { status: 0, stdout: "", stderr: "" };
    }
    const current = replicas.shift() ?? 1;
    return {
      status: 0,
      stdout: JSON.stringify({ status: { currentReplicas: current } }),
      stderr: "",
    };
  };
  const ok = await runCluster({
    exec,
    sleep: async () => {},
    log: () => {},
    namespace: "awap-dev",
  });
  assert.equal(ok, true);
  assert.ok(calls.some((args) => args.includes("run")));
  assert.ok(calls.some((args) => args.includes("delete")));
});

test("runCluster fails when the load pod cannot be created", async () => {
  const exec = () => ({ status: 1, stdout: "", stderr: "forbidden" });
  const ok = await runCluster({ exec, sleep: async () => {}, log: () => {} });
  assert.equal(ok, false);
});

test("main dry-run passes and the usage path returns 2", async () => {
  const cwd = process.cwd();
  assert.equal(await main(["--dry-run"], cwd, undefined, undefined, () => {}), 0);
  assert.equal(await main([], cwd, undefined, undefined, () => {}), 2);
});
