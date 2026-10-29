package com.awap.webhook.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Domain state machine and lease rules of the durable webhook delivery. */
class WebhookDeliveryTest {

  private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
  private static final Duration LEASE = Duration.ofSeconds(30);
  private final UUID tenantId = UUID.randomUUID();
  private final UUID instanceId = UUID.randomUUID();
  private final UUID executionId = UUID.randomUUID();

  private WebhookDelivery pending() {
    return WebhookDelivery.pending(
        tenantId,
        instanceId,
        executionId,
        "hook",
        "https://hooks.example.com/path?token=secret",
        "{\"a\":1}",
        WebhookDelivery.idempotencyKey(instanceId, "hook", executionId),
        1,
        3,
        500,
        "target returned status 500.",
        NOW,
        NOW.plusSeconds(1),
        NOW.plus(Duration.ofDays(7)));
  }

  @Test
  void pendingDeliveryIsDueAfterItsFirstBackoffAndKeepsTheImmutableIdentity() {
    WebhookDelivery delivery = pending();

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
    assertThat(delivery.getAttempts()).isEqualTo(1);
    assertThat(delivery.getMaxAttempts()).isEqualTo(3);
    assertThat(delivery.isDueAt(NOW)).isFalse();
    assertThat(delivery.isDueAt(NOW.plusSeconds(1))).isTrue();
    assertThat(delivery.getIdempotencyKey()).isEqualTo(instanceId + ":hook:" + executionId);
    assertThat(delivery.getTargetUrl()).contains("?token=secret");
    assertThat(delivery.sanitizedTargetUrl()).isEqualTo("https://hooks.example.com/path");
    assertThat(delivery.payloadSizeBytes()).isEqualTo(7);
  }

  @Test
  void pendingDeliveryClampsPastDueInstantsToNow() {
    WebhookDelivery delivery =
        WebhookDelivery.pending(
            tenantId,
            instanceId,
            executionId,
            "hook",
            "https://hooks.example.com",
            "{}",
            "key",
            1,
            3,
            500,
            "err",
            NOW,
            NOW.minusSeconds(60),
            NOW.plus(Duration.ofDays(1)));

    assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW);
    assertThat(delivery.isDueAt(NOW)).isTrue();
  }

  @Test
  void claimLeasesTheRowAndIncrementsAttempts() {
    WebhookDelivery delivery = pending();

    delivery.claim("token-a", NOW.plusSeconds(1), LEASE);

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRYING);
    assertThat(delivery.getAttempts()).isEqualTo(2);
    assertThat(delivery.getLeaseToken()).contains("token-a");
    assertThat(delivery.getLeaseUntil()).contains(NOW.plusSeconds(31));
    assertThat(delivery.isDueAt(NOW.plusSeconds(2))).isFalse();
  }

  @Test
  void claimFailsWhileAnotherReplicaHoldsALiveLease() {
    WebhookDelivery delivery = pending();
    delivery.claim("token-a", NOW.plusSeconds(1), LEASE);

    assertThatThrownBy(() -> delivery.claim("token-b", NOW.plusSeconds(5), LEASE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("leased by another replica");
  }

  @Test
  void claimSucceedsAfterTheLeaseExpires() {
    WebhookDelivery delivery = pending();
    delivery.claim("token-a", NOW.plusSeconds(1), LEASE);

    delivery.claim("token-b", NOW.plusSeconds(40), LEASE);

    assertThat(delivery.getLeaseToken()).contains("token-b");
    assertThat(delivery.getAttempts()).isEqualTo(3);
  }

  @Test
  void leaseOwnerCanRecordSuccessAndSentIsTerminal() {
    WebhookDelivery delivery = pending();
    delivery.claim("token-a", NOW.plusSeconds(1), LEASE);

    delivery.recordSent(204, NOW.plusSeconds(2));

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.SENT);
    assertThat(delivery.getLastStatusCode()).hasValue(204);
    assertThat(delivery.getLastError()).isEmpty();
    assertThat(delivery.getLeaseToken()).isEmpty();
    assertThatThrownBy(() -> delivery.claim("token-c", NOW.plusSeconds(3), LEASE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SENT");
  }

  @Test
  void recordingAnOutcomeAfterTheLeaseExpiredIsRejected() {
    WebhookDelivery delivery = pending();
    delivery.claim("token-a", NOW.plusSeconds(1), LEASE);

    assertThatThrownBy(() -> delivery.recordSent(200, NOW.plusSeconds(60)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease expired");
  }

  @Test
  void recordingWithoutALeaseIsRejected() {
    WebhookDelivery delivery = pending();

    assertThatThrownBy(() -> delivery.recordSent(200, NOW.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not RETRYING");
  }

  @Test
  void scheduleRetryNeverSetsADueInstantInThePast() {
    WebhookDelivery delivery = pending();
    delivery.claim("token-a", NOW.plusSeconds(1), LEASE);

    delivery.scheduleRetry(503, "unavailable", NOW.minusSeconds(30), NOW.plusSeconds(2));

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRYING);
    assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
    assertThat(delivery.getLeaseToken()).isEmpty();
    assertThat(delivery.getLastStatusCode()).hasValue(503);
    assertThat(delivery.getAttempts()).isEqualTo(2);
    assertThat(delivery.hasAttemptsLeft()).isTrue();
  }

  @Test
  void exhaustedDeliveryHasNoAttemptsLeft() {
    WebhookDelivery delivery = pending();
    delivery.claim("token-a", NOW.plusSeconds(1), LEASE);
    delivery.claim("token-b", NOW.plusSeconds(40), LEASE);

    assertThat(delivery.getAttempts()).isEqualTo(3);
    assertThat(delivery.hasAttemptsLeft()).isFalse();
  }

  @Test
  void markDeadIsTerminalAndClearsTheLease() {
    WebhookDelivery delivery = pending();
    delivery.claim("token-a", NOW.plusSeconds(1), LEASE);

    delivery.markDead(500, "exhausted", NOW.plusSeconds(2));

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
    assertThat(delivery.getLeaseToken()).isEmpty();
    assertThat(delivery.isDueAt(NOW.plusSeconds(3))).isFalse();
    assertThatThrownBy(() -> delivery.claim("token-b", NOW.plusSeconds(3), LEASE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void cancelledDeliveryCannotBeClaimedAgain() {
    WebhookDelivery delivery = pending();

    delivery.cancel(NOW.plusSeconds(1));

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.CANCELLED);
    assertThat(delivery.isDueAt(NOW.plusSeconds(2))).isFalse();
    assertThatThrownBy(() -> delivery.claim("token-a", NOW.plusSeconds(2), LEASE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void cancellingIsIdempotentButASentDeliveryCannotBeCancelled() {
    WebhookDelivery delivery = pending();
    delivery.cancel(NOW.plusSeconds(1));
    delivery.cancel(NOW.plusSeconds(2));
    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.CANCELLED);

    WebhookDelivery sent = pending();
    sent.claim("token-a", NOW.plusSeconds(1), LEASE);
    sent.recordSent(200, NOW.plusSeconds(2));

    assertThatThrownBy(() -> sent.cancel(NOW.plusSeconds(3)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be cancelled");
  }

  @Test
  void expiredDeliveryIsNotDue() {
    WebhookDelivery delivery =
        WebhookDelivery.pending(
            tenantId,
            instanceId,
            executionId,
            "hook",
            "https://hooks.example.com",
            "{}",
            "key",
            1,
            3,
            500,
            "err",
            NOW,
            NOW,
            NOW.plusSeconds(10));

    assertThat(delivery.isDueAt(NOW.plusSeconds(5))).isTrue();
    assertThat(delivery.isDueAt(NOW.plusSeconds(11))).isFalse();
    assertThat(delivery.hasExpired(NOW.plusSeconds(11))).isTrue();
    assertThatThrownBy(() -> delivery.claim("token", NOW.plusSeconds(11), LEASE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void blankIdentityFieldsAreRejected() {
    assertThatThrownBy(
            () ->
                WebhookDelivery.pending(
                    tenantId,
                    instanceId,
                    executionId,
                    "hook",
                    " ",
                    "{}",
                    "key",
                    1,
                    3,
                    500,
                    "err",
                    NOW,
                    NOW,
                    NOW.plusSeconds(10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("targetUrl");
  }

  @Test
  void rehydratedRowKeepsItsRecordedState() {
    WebhookDelivery delivery =
        WebhookDelivery.rehydrate(
            UUID.randomUUID(),
            tenantId,
            instanceId,
            executionId,
            "hook",
            "https://hooks.example.com",
            "{}",
            "key",
            WebhookDeliveryStatus.DEAD,
            3,
            3,
            NOW,
            null,
            null,
            400,
            "bad request",
            NOW.minusSeconds(60),
            NOW,
            NOW.plus(Duration.ofDays(1)));

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
    assertThat(delivery.getAttempts()).isEqualTo(3);
    assertThat(delivery.getLastError()).contains("bad request");
    assertThat(delivery.hasAttemptsLeft()).isFalse();
  }
}
