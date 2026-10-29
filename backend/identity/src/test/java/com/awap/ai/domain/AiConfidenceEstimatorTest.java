package com.awap.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiConfidenceEstimatorTest {

  @Test
  void emptyOutputHasZeroConfidence() {
    assertThat(AiConfidenceEstimator.estimate(null)).isZero();
    assertThat(AiConfidenceEstimator.estimate("  ")).isZero();
  }

  @Test
  void substantiveOutputClearsTheDefaultThreshold() {
    assertThat(AiConfidenceEstimator.estimate("CLASSIFY: invoice for Acme Corp"))
        .isGreaterThanOrEqualTo(0.7);
  }

  @Test
  void hedgingPenaltiesAreCumulativeAndClamped() {
    assertThat(AiConfidenceEstimator.estimate("maybe unclear")).isBetween(0.2, 0.3);
    assertThat(AiConfidenceEstimator.estimate("maybe maybe maybe maybe unclear"))
        .isBetween(0.0, 0.7);
  }

  @Test
  void shortAnswersDoNotGetTheLengthBonus() {
    assertThat(AiConfidenceEstimator.estimate("yes")).isEqualTo(0.85);
  }
}
