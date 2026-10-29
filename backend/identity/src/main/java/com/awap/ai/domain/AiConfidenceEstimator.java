package com.awap.ai.domain;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic confidence estimate for a model completion. The estimate is intentionally simple
 * and explainable (no hidden model): it penalises hedging/empty output and rewards a substantive
 * answer, so the HITL threshold has a stated, reproducible basis.
 */
public final class AiConfidenceEstimator {

  private static final List<String> HEDGES =
      List.of("maybe", "unclear", "not sure", "possibly", "i think", "cannot determine");

  private AiConfidenceEstimator() {}

  /** Returns a confidence in [0, 1] for the completion. */
  public static double estimate(String output) {
    if (output == null || output.isBlank()) {
      return 0.0;
    }
    String normalized = output.toLowerCase(Locale.ROOT);
    double confidence = 0.85;
    for (String hedge : HEDGES) {
      if (normalized.contains(hedge)) {
        confidence -= 0.3;
      }
    }
    if (normalized.length() > 20) {
      confidence += 0.05;
    }
    return Math.max(0.0, Math.min(1.0, confidence));
  }
}
