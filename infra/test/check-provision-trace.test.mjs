import assert from "node:assert/strict";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

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
