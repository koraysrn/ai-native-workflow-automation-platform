package com.awap.webhook.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.webhook.domain.WebhookDelivery;
import com.awap.webhook.domain.WebhookDeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Idempotency, tenancy and lifecycle behaviour of the delivery use-case service. */
class WebhookDeliveryServiceTest {

  private static final Instant NOW = Instant.parse("2026-03-01T08:00:00Z");

  private final InMemoryWebhookDeliveryStore store = new InMemoryWebhookDeliveryStore();
  private final WebhookRetryPolicy policy =
      new WebhookRetryPolicy(
          10, new long[] {1_000L, 5_000L, 25_000L, 120_000L, 600_000L}, 0.2, () -> 0.0);
  private final WebhookDeliveryService service =
      new WebhookDeliveryService(store, policy, 7, 25, Duration.ofSeconds(30));

  private WebhookDeliveryService.PendingDelivery request(UUID tenantId, int maxAttempts) {
    UUID instanceId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    return new WebhookDeliveryService.PendingDelivery(
        tenantId,
        instanceId,
        executionId,
        "hook",
        "https://hooks.example.com/path",
        "{\"a\":1}",
        WebhookDelivery.idempotencyKey(instanceId, "hook", executionId),
        1,
        maxAttempts,
        500,
        "target returned status 500.");
  }

  @Test
  void schedulesTheFirstRetryWithTheConfiguredBackoff() {
    UUID tenantId = UUID.randomUUID();
    WebhookDeliveryService.PendingDelivery request = request(tenantId, 3);

    WebhookDelivery delivery = service.scheduleRetryAfterFailure(request, NOW);

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
    assertThat(delivery.getAttempts()).isEqualTo(1);
    assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
    assertThat(delivery.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    assertThat(delivery.getTenantId()).isEqualTo(tenantId);
    assertThat(delivery.getIdempotencyKey()).isEqualTo(request.idempotencyKey());
    assertThat(store.saveCalls()).isEqualTo(1);
  }

  @Test
  void schedulingTheSameDeliveryTwiceReusesTheExistingRow() {
    UUID tenantId = UUID.randomUUID();
    WebhookDeliveryService.PendingDelivery request = request(tenantId, 3);

    WebhookDelivery first = service.scheduleRetryAfterFailure(request, NOW);
    WebhookDelivery second = service.scheduleRetryAfterFailure(request, NOW.plusSeconds(90));

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(store.allRows()).hasSize(1);
    assertThat(store.saveCalls()).isEqualTo(1);
  }

  @Test
  void losingTheUniqueKeyRaceReturnsTheCompetingRow() {
    UUID tenantId = UUID.randomUUID();
    WebhookDeliveryService.PendingDelivery request = request(tenantId, 3);
    WebhookDelivery competing =
        WebhookDelivery.pending(
            tenantId,
            request.workflowInstanceId(),
            request.nodeExecutionId(),
            request.nodeId(),
            request.targetUrl(),
            request.payloadJson(),
            request.idempotencyKey(),
            1,
            3,
            500,
            "race winner",
            NOW,
            NOW.plusSeconds(1),
            NOW.plus(Duration.ofDays(7)));
    store.insertThenFailOnNextSave(competing);

    WebhookDelivery result = service.scheduleRetryAfterFailure(request, NOW);

    assertThat(result.getId()).isEqualTo(competing.getId());
    assertThat(store.allRows()).hasSize(1);
  }

  @Test
  void permanentFailureIsPersistedAsDead() {
    WebhookDeliveryService.PendingDelivery request = request(UUID.randomUUID(), 3);

    WebhookDelivery delivery = service.recordPermanentFailure(request, NOW);

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
    assertThat(delivery.getAttempts()).isEqualTo(1);
    assertThat(delivery.getLastError()).contains("target returned status 500.");
    assertThat(delivery.isDueAt(NOW.plusSeconds(3600))).isFalse();
  }

  @Test
  void claimDueReturnsOnlyRowsWhoseRetryInstantElapsed() {
    service.scheduleRetryAfterFailure(request(UUID.randomUUID(), 3), NOW);

    assertThat(service.claimDue(NOW)).isEmpty();
    assertThat(service.claimDue(NOW.plusSeconds(2))).hasSize(1);
    assertThat(service.claimDue(NOW.plusSeconds(3))).isEmpty();
  }

  @Test
  void listIsTenantScopedAndFiltersByStatus() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    service.scheduleRetryAfterFailure(request(tenantA, 3), NOW);
    service.recordPermanentFailure(request(tenantA, 3), NOW);
    service.scheduleRetryAfterFailure(request(tenantB, 3), NOW);

    List<WebhookDelivery> tenantARows = service.list(tenantA, Optional.empty(), 50);
    List<WebhookDelivery> tenantADead =
        service.list(tenantA, Optional.of(WebhookDeliveryStatus.DEAD), 50);
    List<WebhookDelivery> tenantBRows = service.list(tenantB, Optional.empty(), 50);

    assertThat(tenantARows).hasSize(2).allMatch(row -> row.getTenantId().equals(tenantA));
    assertThat(tenantADead).hasSize(1);
    assertThat(tenantADead.get(0).getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
    assertThat(tenantBRows).hasSize(1).allMatch(row -> row.getTenantId().equals(tenantB));
  }

  @Test
  void cancellingAnInstanceStopsItsOpenDeliveries() {
    UUID tenantId = UUID.randomUUID();
    UUID instanceId = UUID.randomUUID();
    WebhookDeliveryService.PendingDelivery first =
        new WebhookDeliveryService.PendingDelivery(
            tenantId,
            instanceId,
            UUID.randomUUID(),
            "hook",
            "https://hooks.example.com",
            "{}",
            instanceId + ":hook:1",
            1,
            3,
            500,
            "err");
    service.scheduleRetryAfterFailure(first, NOW);

    assertThat(service.cancelForInstance(tenantId, instanceId, NOW.plusSeconds(5))).isEqualTo(1);
    assertThat(service.cancelForInstance(tenantId, instanceId, NOW.plusSeconds(6))).isZero();
    assertThat(service.claimDue(NOW.plusSeconds(10))).isEmpty();
    assertThat(service.list(tenantId, Optional.empty(), 10).get(0).getStatus())
        .isEqualTo(WebhookDeliveryStatus.CANCELLED);
  }

  @Test
  void cancellingAnInstanceNeverTouchesAnotherTenant() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID instanceId = UUID.randomUUID();
    service.scheduleRetryAfterFailure(
        new WebhookDeliveryService.PendingDelivery(
            tenantB,
            instanceId,
            UUID.randomUUID(),
            "hook",
            "https://hooks.example.com",
            "{}",
            "key-b",
            1,
            3,
            500,
            "err"),
        NOW);

    assertThat(service.cancelForInstance(tenantA, instanceId, NOW.plusSeconds(5))).isZero();
    assertThat(service.list(tenantB, Optional.empty(), 10).get(0).getStatus())
        .isEqualTo(WebhookDeliveryStatus.PENDING);
  }

  @Test
  void expiredRowsAreCancelledByTheSweep() {
    WebhookDelivery delivery =
        WebhookDelivery.rehydrate(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "hook",
            "https://hooks.example.com",
            "{}",
            "expired-key",
            WebhookDeliveryStatus.PENDING,
            1,
            3,
            NOW,
            null,
            null,
            500,
            "err",
            NOW.minus(Duration.ofDays(8)),
            NOW.minus(Duration.ofDays(8)),
            NOW.minus(Duration.ofDays(1)));
    store.save(delivery);

    assertThat(service.expireStale(NOW)).isEqualTo(1);
    assertThat(service.list(delivery.getTenantId(), Optional.empty(), 10).get(0).getStatus())
        .isEqualTo(WebhookDeliveryStatus.CANCELLED);
  }

  @Test
  void saveFailureWithoutACompetingRowIsPropagated() {
    WebhookDeliveryService.PendingDelivery request = request(UUID.randomUUID(), 3);
    store.failNextSaveWith(new DataIntegrityViolationException("unexpected"));

    assertThatThrownBy(() -> service.scheduleRetryAfterFailure(request, NOW))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
