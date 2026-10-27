import assert from "node:assert/strict";
import { test } from "node:test";
import {
  CDC_TOPIC_MAP,
  CDC_TRIGGERS,
  cdcTopicFor,
  evaluateCutover,
  evaluateRollback,
  percentile,
} from "../lib/cdc-triggers.mjs";

const healthy = {
  publishLagP95Ms: 800,
  consecutiveBacklogSweeps: 0,
  slotLagBytes: 1024,
  walGrowthBytesPerMin: 1024,
};

test("thresholds match the evaluation document", () => {
  assert.equal(CDC_TRIGGERS.publishLagP95Ms, 5_000);
  assert.equal(CDC_TRIGGERS.backlogSweeps, 3);
  assert.equal(CDC_TRIGGERS.slotLagBytes, 512 * 1024 * 1024);
  assert.equal(CDC_TRIGGERS.walGrowthBytesPerMin, 128 * 1024 * 1024);
});

test("no cutover while every measurement stays inside the budget", () => {
  assert.deepEqual(evaluateCutover(healthy), { cutover: false, reasons: [] });
});

test("each measured trigger independently forces a cutover", () => {
  const cases = [
    { publishLagP95Ms: 6_000 },
    { consecutiveBacklogSweeps: 3 },
    { slotLagBytes: 600 * 1024 * 1024 },
    { walGrowthBytesPerMin: 200 * 1024 * 1024 },
  ];
  for (const override of cases) {
    const verdict = evaluateCutover({ ...healthy, ...override });
    assert.equal(verdict.cutover, true, `expected cutover for ${JSON.stringify(override)}`);
    assert.equal(verdict.reasons.length, 1);
  }
});

test("rollback triggers on a dead connector, duplicates or consumer errors", () => {
  assert.deepEqual(
    evaluateRollback({ connectorRunning: true, duplicateRate: 0.01, consumerErrorRate: 0 }),
    { rollback: false, reasons: [] },
  );
  assert.equal(
    evaluateRollback({ connectorRunning: false, duplicateRate: 0, consumerErrorRate: 0 }).rollback,
    true,
  );
  assert.match(
    evaluateRollback({ connectorRunning: true, duplicateRate: 0.2, consumerErrorRate: 0 }).reasons[0],
    /duplicate rate/,
  );
  assert.match(
    evaluateRollback({ connectorRunning: true, duplicateRate: 0, consumerErrorRate: 0.1 }).reasons[0],
    /consumer error rate/,
  );
});

test("topic routing matches the documented compatibility map", () => {
  for (const [aggregateType, topic] of Object.entries(CDC_TOPIC_MAP)) {
    assert.equal(cdcTopicFor(aggregateType), topic);
  }
  assert.equal(cdcTopicFor("Task"), "outbox.event.task");
});

test("percentile uses nearest-rank and tolerates empty samples", () => {
  assert.equal(percentile([], 95), 0);
  assert.equal(percentile([10], 95), 10);
  assert.equal(percentile([5, 1, 9, 3, 7], 50), 5);
  assert.equal(percentile([5, 1, 9, 3, 7], 95), 9);
});
