package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NodeTimeoutSettingsTest {

  private final NodeTimeoutSettings settings = new NodeTimeoutSettings(30, 3600, 8, 64);

  @Test
  void exposesTheDefaultBudgetAndBounds() {
    assertThat(settings.defaultTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(settings.isWithinBounds(1)).isTrue();
    assertThat(settings.isWithinBounds(3600)).isTrue();
    assertThat(settings.isWithinBounds(0)).isFalse();
    assertThat(settings.isWithinBounds(-1)).isFalse();
    assertThat(settings.isWithinBounds(3601)).isFalse();
  }

  @Test
  void rejectsAZeroDefault() {
    assertThatThrownBy(() -> new NodeTimeoutSettings(0, 3600, 8, 64))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("default-seconds")
        .hasMessageContaining("between 1 and 3600");
  }

  @Test
  void rejectsANegativeDefault() {
    assertThatThrownBy(() -> new NodeTimeoutSettings(-5, 3600, 8, 64))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("default-seconds");
  }

  @Test
  void rejectsADefaultAboveTheMaximum() {
    assertThatThrownBy(() -> new NodeTimeoutSettings(7200, 3600, 8, 64))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("default-seconds");
  }

  @Test
  void rejectsAZeroMaximum() {
    assertThatThrownBy(() -> new NodeTimeoutSettings(30, 0, 8, 64))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max-seconds");
  }

  @Test
  void rejectsAWorkerPoolOutsideTheAllowedRange() {
    assertThatThrownBy(() -> new NodeTimeoutSettings(30, 3600, 0, 64))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("worker-threads");
    assertThatThrownBy(() -> new NodeTimeoutSettings(30, 3600, 65, 64))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("worker-threads");
  }

  @Test
  void rejectsANegativeQueueCapacity() {
    assertThatThrownBy(() -> new NodeTimeoutSettings(30, 3600, 8, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("queue-capacity");
    assertThatThrownBy(() -> new NodeTimeoutSettings(30, 3600, 8, 10_001))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("queue-capacity");
  }

  @Test
  void defaultsAreValid() {
    assertThat(NodeTimeoutSettings.defaults())
        .isEqualTo(new NodeTimeoutSettings(30, NodeTimeoutSettings.DEFAULT_MAX_SECONDS, 8, 64));
  }
}
