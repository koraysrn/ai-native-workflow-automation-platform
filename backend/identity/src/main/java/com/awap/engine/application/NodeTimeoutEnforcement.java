package com.awap.engine.application;

/**
 * The guarantee the engine can actually provide when a node exceeds its timeout budget.
 *
 * <p>Node timeout is only a hard guarantee when the node's work is interrupt-safe, i.e. an outbound
 * call that can be abandoned without touching a database transaction. Arbitrary Java node code that
 * owns the current transaction cannot be stopped safely; for those node types the engine only
 * measures and reports the breach.
 */
public enum NodeTimeoutEnforcement {

  /** Work runs on a bounded worker and is cancelled with {@code Future.cancel(true)} on breach. */
  ENFORCED(true),

  /** Work runs on the caller thread; the breach is measured after the work returns. */
  BEST_EFFORT(false),

  /** The node type has no interruptible work, so its timeout configuration is ignored. */
  UNSUPPORTED(false);

  private final boolean hardGuarantee;

  NodeTimeoutEnforcement(boolean hardGuarantee) {
    this.hardGuarantee = hardGuarantee;
  }

  /** True when the engine guarantees that work stops at (roughly) the configured budget. */
  public boolean hardGuarantee() {
    return hardGuarantee;
  }
}
