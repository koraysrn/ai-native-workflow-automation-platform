import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { metricBase, metricTokens } from "../lib/metrics.mjs";

const DASHBOARD = new URL("../provisioning/dashboards/awap.json", import.meta.url);
const CATALOG = new URL("../metric-catalog.json", import.meta.url);

async function loadJson(url) {
  return JSON.parse(await readFile(url, "utf8"));
}

test("dashboard JSON parses and has unique panel ids", async () => {
  const dashboard = await loadJson(DASHBOARD);
  assert.equal(dashboard.uid, "awap-overview");
  assert.ok(Array.isArray(dashboard.panels) && dashboard.panels.length >= 9);
  const ids = dashboard.panels.map((panel) => panel.id);
  assert.equal(new Set(ids).size, ids.length, "panel ids must be unique");
});

test("dashboard covers every required panel from the issue", async () => {
  const dashboard = await loadJson(DASHBOARD);
  const titles = dashboard.panels.map((panel) => panel.title);
  for (const expected of [
    "Workflow success vs failure rate",
    "Workflow failure ratio",
    "Task completion rate",
    "Task decisions",
    "Workflow process duration p95",
    "Notification deliveries and failures",
    "Notification retry lag and dispatch latency",
    "Outbox backlog, relay rate and DLQ",
  ]) {
    assert.ok(titles.includes(expected), `missing panel: ${expected}`);
  }
});

test("tenant and channel template variables filter the panels", async () => {
  const dashboard = await loadJson(DASHBOARD);
  const variables = dashboard.templating.list.map((variable) => variable.name);
  assert.deepEqual(variables, ["tenant", "channel"]);
  for (const variable of dashboard.templating.list) {
    assert.equal(variable.includeAll, true);
    assert.equal(variable.type, "query");
    assert.ok(variable.query.query.startsWith("label_values("));
  }
  const filtered = dashboard.panels.flatMap((panel) => panel.targets).filter((target) =>
    String(target.expr).includes("$tenant") || String(target.expr).includes("$channel"),
  );
  assert.ok(filtered.length >= 6, "most panels must honour the tenant/channel variables");
});

test("every panel query references a catalogued metric", async () => {
  const dashboard = await loadJson(DASHBOARD);
  const catalog = new Set(
    (await loadJson(CATALOG)).metrics.map((entry) => metricBase(entry.name)),
  );
  const missing = [];
  for (const panel of dashboard.panels) {
    assert.ok(Array.isArray(panel.targets) && panel.targets.length > 0, `${panel.title} has no query`);
    for (const target of panel.targets) {
      assert.ok(String(target.expr).trim().length > 0);
      for (const token of metricTokens(target.expr)) {
        if (!catalog.has(metricBase(token))) {
          missing.push(`${panel.title}: ${token}`);
        }
      }
    }
  }
  assert.deepEqual(missing, [], `uncatalogued metrics: ${missing.join(", ")}`);
});

test("every panel uses the provisioned Prometheus datasource", async () => {
  const dashboard = await loadJson(DASHBOARD);
  for (const panel of dashboard.panels) {
    assert.deepEqual(panel.datasource, { type: "prometheus", uid: "prometheus" });
  }
  assert.ok(dashboard.links.some((link) => link.url.includes("operations-runbook.md")));
});
