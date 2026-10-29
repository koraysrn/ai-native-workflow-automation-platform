package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NodeTimeoutConfigurationTest {

  private final NodeTimeoutConfiguration configuration = new NodeTimeoutConfiguration();

  @Test
  void bindsValidValues() {
    NodeTimeoutSettings settings = configuration.nodeTimeoutSettings(45, 7200, 4, 16);

    assertThat(settings.defaultSeconds()).isEqualTo(45);
    assertThat(settings.maxSeconds()).isEqualTo(7200);
    assertThat(settings.workerThreads()).isEqualTo(4);
    assertThat(settings.queueCapacity()).isEqualTo(16);
  }

  @Test
  void rejectsATwentyFourHourTimeout() {
    assertThatThrownBy(() -> configuration.nodeTimeoutSettings(86400, 3600, 8, 64))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("default-seconds")
        .hasMessageContaining("86400");
  }

  @Test
  void rejectsAZeroTimeout() {
    assertThatThrownBy(() -> configuration.nodeTimeoutSettings(0, 3600, 8, 64))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("default-seconds");
  }

  @Test
  void rejectsAnUnboundedMaximum() {
    assertThatThrownBy(() -> configuration.nodeTimeoutSettings(30, 0, 8, 64))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max-seconds");
  }
}
