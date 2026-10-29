import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { metricBase, metricTokens } from "../../grafana/lib/metrics.mjs";

const RULES = new URL("../alert-rules.yml", import.meta.url);
const CATALOG = new URL("../../grafana/metric-catalog.json", import.meta.url);

async function ruleFile() {
  return readFile(RULES, "utf8");
}

/** Splits the YAML into one block per `- alert:` entry (the file is a flat rule list). */
export function alertBlocks(yaml) {
  return yaml
    .split(/\n(?=      - alert: )/)
    .filter((block) => block.includes("- alert: "))
    .map((block) => block.trim());
}

test("every alert carries severity, summary, description and a runbook link", async () => {
  const blocks = alertBlocks(await ruleFile());
  assert.ok(blocks.length >= 7, `expected the existing and new alerts, got ${blocks.length}`);
  for (const block of blocks) {
    const name = block.match(/- alert: (\S+)/)[1];
    assert.match(block, /labels:\n\s+severity: (warning|critical)/, `${name} lacks severity`);
    assert.match(block, /summary: "/, `${name} lacks a summary`);
    assert.match(block, /description: "/, `${name} lacks a description`);
    assert.match(block, /runbook_url: "https:\/\/github\.com\/koraysrn\//, `${name} lacks a runbook URL`);
    assert.match(block, new RegExp(`runbook_url: ".*operations-runbook\\.md#`), `${name} runbook must be anchored`);
  }
});

test("new workflow, notification, webhook and outbox alerts exist", async () => {
  const yaml = await ruleFile();
  for (const alert of [
    "AwapWorkflowFailureRateHigh",
    "AwapNotificationRetryLagHigh",
    "AwapWebhookDeadGrowth",
    "AwapOutboxBacklogStuck",
  ]) {
    assert.ok(yaml.includes(`- alert: ${alert}`), `missing alert ${alert}`);
  }
});

test("alert expressions only reference catalogued metrics", async () => {
  const catalog = new Set(
    JSON.parse(await readFile(CATALOG, "utf8")).metrics.map((entry) => metricBase(entry.name)),
  );
  const missing = [];
  for (const block of alertBlocks(await ruleFile())) {
    const expr = block.split("expr:")[1]?.split("for:")[0] ?? "";
    for (const token of metricTokens(expr)) {
      if (!catalog.has(metricBase(token))) {
        missing.push(token);
      }
    }
  }
  assert.deepEqual(missing, [], `uncatalogued metrics in alerts: ${missing.join(", ")}`);
});

test("transient spikes are dampened with a for: window", async () => {
  for (const block of alertBlocks(await ruleFile())) {
    const name = block.match(/- alert: (\S+)/)[1];
    assert.match(block, /for: \d+[mh]/, `${name} must define a for: window`);
  }
});
