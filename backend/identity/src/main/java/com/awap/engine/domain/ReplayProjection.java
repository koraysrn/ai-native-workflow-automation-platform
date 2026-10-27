package com.awap.engine.domain;

import java.util.List;
import java.util.Objects;

/** Outcome of folding an execution log: the projected state plus every divergence found. */
public record ReplayProjection(
    ReplayState state, List<ReplayDivergence> divergences, boolean failedClosed, int appliedRows) {

  public ReplayProjection {
    Objects.requireNonNull(state, "state");
    divergences = divergences == null ? List.of() : List.copyOf(divergences);
    if (appliedRows < 0) {
      throw new IllegalArgumentException("appliedRows must not be negative");
    }
  }

  /** True when no divergence was found and projection ran to completion. */
  public boolean consistent() {
    return !failedClosed && divergences.isEmpty();
  }

  public String stateHash() {
    return state.stateHash();
  }
}
