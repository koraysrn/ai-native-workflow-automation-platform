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
  }

  @Test
  void reusesTheLastDelayForAttemptsBeyondTheConfiguredSequence() {
    assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofSeconds(25));
  }

  @Test
  void isExhaustedAfterTheRetryBudgetIsSpent() {
    assertThat(policy.maxAttempts()).isEqualTo(3);
    assertThat(policy.isExhausted(1)).isFalse();
    assertThat(policy.isExhausted(2)).isFalse();
    assertThat(policy.isExhausted(3)).isFalse();
    // The initial attempt plus the three retries all failed: nothing may be scheduled.
    assertThat(policy.isExhausted(4)).isTrue();
    assertThat(policy.isExhausted(5)).isTrue();
  }

  @Test
  void clockRollbackNeverProducesANegativeDelay() {
    Instant now = Instant.parse("2026-01-01T10:00:00Z");
    Instant lastAttemptInTheFuture = now.plusSeconds(30);

    Instant next = policy.nextAttemptAt(now, lastAttemptInTheFuture, 1);

    assertThat(next).isEqualTo(lastAttemptInTheFuture.plusSeconds(1));
    assertThat(Duration.between(now, next)).isPositive();
    assertThat(Duration.between(lastAttemptInTheFuture, next)).isPositive();
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
        NotificationRetryPolicy.fromCommaSeparated("1000, 5000,25000", 3);

    assertThat(parsed.delaysMs()).containsExactly(1_000L, 5_000L, 25_000L);
    assertThat(parsed.delayAfter(3)).isEqualTo(Duration.ofSeconds(25));
  }

  @Test
  void rejectsInvalidConfiguration() {
    assertThatThrownBy(() -> new NotificationRetryPolicy(new long[0], 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new NotificationRetryPolicy(new long[] {1_000L}, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new NotificationRetryPolicy(new long[] {1_000L, 0L}, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strictly positive");
    assertThatThrownBy(() -> new NotificationRetryPolicy(new long[] {-1L}, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strictly positive");
  }

  @Test
  void rejectsBlankOrNonNumericDelaySpecs() {
    assertThatThrownBy(() -> NotificationRetryPolicy.fromCommaSeparated(" ", 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NotificationRetryPolicy.fromCommaSeparated(null, 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NotificationRetryPolicy.fromCommaSeparated("1000,soon", 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("comma-separated");
  }
}
