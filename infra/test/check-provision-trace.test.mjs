import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import {
  REQUIRED_BRANCHES,
  checkTrace,
  missingBranches,
} from "../scripts/check-provision-trace.mjs";

test("missingBranches reports only the branches the dry-run skipped", () => {
  const lines = ["up:start", "up:dry-run", "\n", "provision:kafka-init"];
  assert.deepEqual(missingBranches(lines), [
    "up:complete",
    "provision:minio-init",
    "provision:debezium-init",
    "provision:complete",
    "connector:dry-run",
  ]);
});

test("missingBranches accepts a complete trace", () => {
  assert.deepEqual(missingBranches([...REQUIRED_BRANCHES]), []);
});

test("checkTrace passes for a complete trace file", async () => {
  const directory = await mkdtemp(join(tmpdir(), "awap-trace-"));
  const file = join(directory, "trace.txt");
  await writeFile(file, `${REQUIRED_BRANCHES.join("\n")}\n`, "utf8");
  assert.equal(await checkTrace(file), REQUIRED_BRANCHES.length);
});

test("checkTrace fails with the unexercised branch names", async () => {
  const directory = await mkdtemp(join(tmpdir(), "awap-trace-"));
  const file = join(directory, "trace.txt");
  await writeFile(file, "up:start\n", "utf8");
  await assert.rejects(() => checkTrace(file), /Unexercised provisioning branches: up:dry-run/);
});

test("the script runs as an entry point from a workspace path with spaces", () => {
  // Regression: the old raw `file://${process.argv[1]}` comparison silently skipped main() when
  // the checkout path contained spaces, so CI-only validation could no-op locally.
  const script = fileURLToPath(new URL("../scripts/check-provision-trace.mjs", import.meta.url));
  const result = spawnSync(process.execPath, [script], { encoding: "utf8" });
  assert.equal(result.status, 2);
  assert.match(result.stderr, /Usage: node infra\/scripts\/check-provision-trace/);
});
