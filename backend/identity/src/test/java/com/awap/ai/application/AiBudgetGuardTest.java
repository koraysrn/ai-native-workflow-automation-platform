package com.awap.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiBudgetGuardTest {

  private final UUID tenant = UUID.randomUUID();
  private final AiBudgetGuard guard = new AiBudgetGuard(100);

  @Test
  void reservesUntilTheBudgetIsExhausted() {
    assertThat(guard.reserve(tenant, 60)).isTrue();
    assertThat(guard.reserve(tenant, 40)).isTrue();
    assertThat(guard.used(tenant)).isEqualTo(100);
    assertThat(guard.reserve(tenant, 1)).isFalse();
  }

  @Test
  void budgetsAreIsolatedPerTenant() {
    assertThat(guard.reserve(tenant, 100)).isTrue();
    assertThat(guard.reserve(UUID.randomUUID(), 100)).isTrue();
  }

  @Test
  void resetRestoresTheWindow() {
    assertThat(guard.reserve(tenant, 100)).isTrue();
    guard.reset(tenant);

    assertThat(guard.used(tenant)).isZero();
    assertThat(guard.reserve(tenant, 100)).isTrue();
    assertThat(guard.budget()).isEqualTo(100);
  }
}
