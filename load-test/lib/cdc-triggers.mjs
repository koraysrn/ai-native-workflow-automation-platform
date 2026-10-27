/**
 * Measurable CDC cutover/rollback rules for the outbox (issue #131).
 * These thresholds are the ones documented in docs/outbox-cdc-evaluation.md.
 */
export const CDC_TRIGGERS = Object.freeze({
  publishLagP95Ms: 5_000,
  backlogSweeps: 3,
  slotLagBytes: 512 * 1024 * 1024,
  walGrowthBytesPerMin: 128 * 1024 * 1024,
  duplicateRate: 0.05,
  consumerErrorRate: 0.01,
});

/** Topic the Debezium EventRouter router writes for each aggregate type. */
export function cdcTopicFor(aggregateType) {
  return `outbox.event.${String(aggregateType).toLowerCase()}`;
}

/** Compatibility mapping between the polling relay topics and the CDC topics. */
export const CDC_TOPIC_MAP = Object.freeze({
  TASK: "outbox.event.task",
  FORM: "outbox.event.form",
  WORKFLOW: "outbox.event.workflow",
  NOTIFICATION: "outbox.event.notification",
  DOCUMENT: "outbox.event.document",
  ANALYTICS: "outbox.event.analytics",
});

/**
 * Decides whether the measured numbers justify switching from polling to CDC.
 * Every input is a measurement, not a prediction.
 */
export function evaluateCutover({
  publishLagP95Ms,
  consecutiveBacklogSweeps,
  slotLagBytes,
  walGrowthBytesPerMin,
}) {
  const reasons = [];
  if (publishLagP95Ms > CDC_TRIGGERS.publishLagP95Ms) {
    reasons.push(`outbox publish lag p95 ${publishLagP95Ms}ms > ${CDC_TRIGGERS.publishLagP95Ms}ms`);
  }
  if (consecutiveBacklogSweeps >= CDC_TRIGGERS.backlogSweeps) {
    reasons.push(`${consecutiveBacklogSweeps} consecutive sweeps left a backlog`);
  }
  if (slotLagBytes > CDC_TRIGGERS.slotLagBytes) {
    reasons.push(`replication slot lag ${slotLagBytes}B exceeds the slot budget`);
  }
  if (walGrowthBytesPerMin > CDC_TRIGGERS.walGrowthBytesPerMin) {
    reasons.push(`WAL growth ${walGrowthBytesPerMin}B/min exceeds the budget`);
  }
  return { cutover: reasons.length > 0, reasons };
}

/** Decides whether a running CDC connector must be rolled back to polling. */
export function evaluateRollback({ connectorRunning, duplicateRate, consumerErrorRate }) {
  const reasons = [];
  if (!connectorRunning) {
    reasons.push("connector is not running");
  }
  if (duplicateRate > CDC_TRIGGERS.duplicateRate) {
    reasons.push(`duplicate rate ${duplicateRate} > ${CDC_TRIGGERS.duplicateRate}`);
  }
  if (consumerErrorRate > CDC_TRIGGERS.consumerErrorRate) {
    reasons.push(`consumer error rate ${consumerErrorRate} > ${CDC_TRIGGERS.consumerErrorRate}`);
  }
  return { rollback: reasons.length > 0, reasons };
}

/** Nearest-rank percentile over a numeric sample (returns 0 for an empty sample). */
export function percentile(values, p) {
  if (!Array.isArray(values) || values.length === 0) {
    return 0;
  }
  const sorted = [...values].sort((left, right) => left - right);
  const rank = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.min(sorted.length - 1, Math.max(0, rank))];
}
