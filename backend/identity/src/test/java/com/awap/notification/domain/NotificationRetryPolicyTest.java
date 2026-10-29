package com.awap.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationRetryPolicyTest {

  private final NotificationRetryPolicy policy = NotificationRetryPolicy.defaults();

  @Test
  void usesTheSpecifiedBackoffSequence() {
    assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(1));
    assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofSeconds(5));
    assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofSeconds(25));
    assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofMinutes(2));
    assertThat(policy.delayAfter(5)).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  void isExhaustedAtMaxAttempts() {
    assertThat(policy.maxAttempts()).isEqualTo(5);
    assertThat(policy.isExhausted(4)).isFalse();
    assertThat(policy.isExhausted(5)).isTrue();
    assertThat(policy.isExhausted(6)).isTrue();
  }

  @Test
  void clockRollbackNeverProducesANegativeDelay() {
    Instant now = Instant.parse("2026-01-01T10:00:00Z");
    Instant lastAttemptInTheFuture = now.plusSeconds(30);

    Instant next = policy.nextAttemptAt(now, lastAttemptInTheFuture, 1);

    assertThat(next).isEqualTo(lastAttemptInTheFuture.plusSeconds(1));
    assertThat(Duration.between(now, next).isPositive());
    assertThat(Duration.between(lastAttemptInTheFuture, next).isPositive());
  }

  @Test
  void nextAttemptIsNeverBeforeNowWithoutRollback() {
    Instant now = Instant.parse("2026-01-01T10:00:00Z");

    assertThat(policy.nextAttemptAt(now, null, 2)).isEqualTo(now.plusSeconds(5));
    assertThat(policy.nextAttemptAt(now, now.minusSeconds(10), 2)).isEqualTo(now.plusSeconds(5));
  }

  @Test
  void parsesConfiguredDelays() {
    NotificationRetryPolicy parsed =
        NotificationRetryPolicy.fromCommaSeparated("1000, 5000,25000,120000,600000", 5);

    assertThat(parsed.delaysMs()).containsExactly(1_000L, 5_000L, 25_000L, 120_000L, 600_000L);
    assertThat(parsed.delayAfter(3)).isEqualTo(Duration.ofSeconds(25));
  }

  @Test
  void rejectsInvalidConfiguration() {
    assertThatThrownBy(() -> new NotificationRetryPolicy(new long[0], 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new NotificationRetryPolicy(new long[] {1_000L}, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
