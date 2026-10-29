package com.awap.webhook.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

/** Retry classification, jittered backoff, Retry-After handling and attempt budget. */
class WebhookRetryPolicyTest {

  private static final Instant NOW = Instant.parse("2026-02-01T09:00:00Z");

  private WebhookRetryPolicy policy() {
    return new WebhookRetryPolicy(
        10, new long[] {1_000L, 5_000L, 25_000L, 120_000L, 600_000L}, 0.2, () -> 0.0);
  }

  @Test
  void backoffFollowsOneSecondFiveTwentyFiveTwoMinutesTenMinutes() {
    WebhookRetryPolicy policy = policy();

    assertThat(policy.delayBeforeRetry(1)).isEqualTo(Duration.ofSeconds(1));
    assertThat(policy.delayBeforeRetry(2)).isEqualTo(Duration.ofSeconds(5));
    assertThat(policy.delayBeforeRetry(3)).isEqualTo(Duration.ofSeconds(25));
    assertThat(policy.delayBeforeRetry(4)).isEqualTo(Duration.ofMinutes(2));
    assertThat(policy.delayBeforeRetry(5)).isEqualTo(Duration.ofMinutes(10));
    assertThat(policy.delayBeforeRetry(9)).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  void jitterStaysWithinTwentyPercentOfTheBaseDelay() {
    WebhookRetryPolicy policy = new WebhookRetryPolicy(10, new long[] {1_000L}, 0.2, () -> 1.0);

    assertThat(policy.applyJitter(Duration.ofSeconds(1))).isEqualTo(Duration.ofMillis(1_200));
  }

  @Test
  void jitterNeverProducesAZeroOrNegativeDelay() {
    WebhookRetryPolicy policy = new WebhookRetryPolicy(10, new long[] {1L}, 0.2, () -> 0.0);

    assertThat(policy.applyJitter(Duration.ofMillis(1)).toMillis()).isEqualTo(1);
  }

  @Test
  void clientErrorStatusesArePermanentExceptRequestTimeoutAndRateLimit() {
    WebhookRetryPolicy policy = policy();

    assertThat(policy.isPermanent(400)).isTrue();
    assertThat(policy.isPermanent(401)).isTrue();
    assertThat(policy.isPermanent(403)).isTrue();
    assertThat(policy.isPermanent(404)).isTrue();
    assertThat(policy.isPermanent(422)).isTrue();

    assertThat(policy.isPermanent(408)).isFalse();
    assertThat(policy.isPermanent(429)).isFalse();
    assertThat(policy.isPermanent(500)).isFalse();
    assertThat(policy.isPermanent(502)).isFalse();
    assertThat(policy.isPermanent(503)).isFalse();
    assertThat(policy.isPermanent(504)).isFalse();
    assertThat(policy.isPermanent(WebhookAttemptResult.TRANSPORT_FAILURE)).isFalse();
  }

  @Test
  void retryableFailureSchedulesTheBackoffOfTheNextStep() {
    WebhookRetryPolicy policy = policy();

    WebhookRetryPolicy.RetryDecision decision =
        policy.decide(WebhookAttemptResult.httpError(500, "", null), 1, NOW);

    assertThat(decision.retry()).isTrue();
    assertThat(decision.nextAttemptAt()).contains(NOW.plusSeconds(1));
  }

  @Test
  void permanentFailureStopsWithoutARetryInstant() {
    WebhookRetryPolicy policy = policy();

    WebhookRetryPolicy.RetryDecision decision =
        policy.decide(WebhookAttemptResult.httpError(404, "", null), 1, NOW);

    assertThat(decision.retry()).isFalse();
    assertThat(decision.nextAttemptAt()).isEmpty();
    assertThat(decision.reason()).contains("permanent");
  }

  @Test
  void exhaustedBudgetStopsRetrying() {
    WebhookRetryPolicy policy = new WebhookRetryPolicy(3, new long[] {1_000L}, 0.0, () -> 0.0);

    WebhookRetryPolicy.RetryDecision decision =
        policy.decide(WebhookAttemptResult.httpError(503, "", null), 3, NOW);

    assertThat(decision.retry()).isFalse();
    assertThat(decision.reason()).contains("exhausted");
    assertThat(policy.hasAttemptsLeft(3)).isFalse();
    assertThat(policy.hasAttemptsLeft(2)).isTrue();
  }

  @Test
  void successStopsImmediately() {
    WebhookRetryPolicy.RetryDecision decision =
        policy().decide(WebhookAttemptResult.success(201, "{}"), 2, NOW);

    assertThat(decision.retry()).isFalse();
    assertThat(decision.reason()).isEqualTo("delivered");
  }

  @Test
  void retryAfterSecondsIsHonouredOnRateLimit() {
    WebhookRetryPolicy.RetryDecision decision =
        policy().decide(WebhookAttemptResult.httpError(429, "", "30"), 1, NOW);

    assertThat(decision.retry()).isTrue();
    assertThat(decision.nextAttemptAt()).contains(NOW.plusSeconds(30));
    assertThat(decision.reason()).isEqualTo("retry-after");
  }

  @Test
  void retryAfterHttpDateIsHonouredOnRateLimit() {
    String httpDate =
        ZonedDateTime.ofInstant(NOW.plusSeconds(45), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME);

    WebhookRetryPolicy.RetryDecision decision =
        policy().decide(WebhookAttemptResult.httpError(429, "", httpDate), 1, NOW);

    assertThat(decision.nextAttemptAt()).contains(NOW.plusSeconds(45));
  }

  @Test
  void retryAfterIsIgnoredForNonRateLimitStatuses() {
    WebhookRetryPolicy.RetryDecision decision =
        policy().decide(WebhookAttemptResult.httpError(503, "", "120"), 1, NOW);

    assertThat(decision.reason()).startsWith("backoff");
    assertThat(decision.nextAttemptAt()).contains(NOW.plusSeconds(1));
  }

  @Test
  void malformedRetryAfterFallsBackToTheConfiguredBackoff() {
    WebhookRetryPolicy policy = policy();

    assertThat(policy.parseRetryAfter("soon", NOW)).isEmpty();
    assertThat(policy.parseRetryAfter("-5", NOW)).isEmpty();
    assertThat(policy.parseRetryAfter(null, NOW)).isEmpty();
  }

  @Test
  void retryAfterInThePastNeverProducesANegativeInstant() {
    String pastDate =
        ZonedDateTime.ofInstant(NOW.minusSeconds(600), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME);

    assertThat(policy().parseRetryAfter(pastDate, NOW)).contains(NOW);
  }

  @Test
  void invalidPolicyParametersAreRejected() {
    assertThatThrownBy(() -> new WebhookRetryPolicy(0, new long[] {1L}, 0.2, Math::random))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WebhookRetryPolicy(3, new long[] {}, 0.2, Math::random))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WebhookRetryPolicy(3, new long[] {1L}, 2.0, Math::random))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
