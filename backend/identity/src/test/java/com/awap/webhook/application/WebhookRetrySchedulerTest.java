package com.awap.webhook.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.webhook.domain.WebhookDelivery;
import com.awap.webhook.domain.WebhookDeliveryStatus;
import com.awap.webhook.infrastructure.WebhookClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Claim, attempt, retry, DLQ and concurrency semantics of the durable retry scheduler. */
class WebhookRetrySchedulerTest {

  private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");

  private final InMemoryWebhookDeliveryStore store = new InMemoryWebhookDeliveryStore();
  private final WebhookRetryPolicy policy =
      new WebhookRetryPolicy(
          10, new long[] {1_000L, 5_000L, 25_000L, 120_000L, 600_000L}, 0.2, () -> 0.0);
  private final WebhookDeliveryService service =
      new WebhookDeliveryService(store, policy, 7, 25, Duration.ofSeconds(30));
  private final WebhookClient client = mock(WebhookClient.class);
  private final WebhookDeliveryNodePort nodePort = mock(WebhookDeliveryNodePort.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final WebhookDeliveryMetrics metrics = new WebhookDeliveryMetrics(registry);
  private final WebhookDeliveryAttemptRunner runner =
      new WebhookDeliveryAttemptRunner(
          client, service, policy, metrics, nodePort, Duration.ofSeconds(5));
  private final WebhookRetryScheduler scheduler = new WebhookRetryScheduler(service, runner);

  private WebhookDelivery scheduleDelivery(UUID tenantId, int maxAttempts) {
    UUID instanceId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    return service.scheduleRetryAfterFailure(
        new WebhookDeliveryService.PendingDelivery(
            tenantId,
            instanceId,
            executionId,
            "hook",
            "https://hooks.example.com/path?secret=1",
            "{\"payload\":true}",
            WebhookDelivery.idempotencyKey(instanceId, "hook", executionId),
            1,
            maxAttempts,
            500,
            "target returned status 500."),
        NOW);
  }

  private double attemptsCount(String result) {
    return java.util.Optional.ofNullable(
            registry.find("awap.webhook.attempts").tag("result", result).counter())
        .map(counter -> counter.count())
        .orElse(0.0);
  }

  @Test
  void successfulRetryMarksSentAndCompletesTheNode() {
    WebhookDelivery delivery = scheduleDelivery(UUID.randomUUID(), 3);
    when(client.attempt(
            eq(delivery.getTargetUrl()),
            eq(delivery.getPayloadJson()),
            eq(delivery.getIdempotencyKey()),
            any(Duration.class)))
        .thenReturn(WebhookAttemptResult.success(200, "{\"ok\":true}"));

    int claimed = scheduler.runOnce(NOW.plusSeconds(2));

    assertThat(claimed).isEqualTo(1);
    WebhookDelivery stored = store.allRows().get(0);
    assertThat(stored.getStatus()).isEqualTo(WebhookDeliveryStatus.SENT);
    assertThat(stored.getAttempts()).isEqualTo(2);
    verify(nodePort)
        .nodeCompleted(
            delivery.getTenantId(),
            delivery.getWorkflowInstanceId(),
            delivery.getNodeExecutionId(),
            delivery.getId(),
            "{\"ok\":true}",
            200);
    assertThat(attemptsCount("sent")).isEqualTo(1.0);
    assertThat(registry.get("awap.webhook.retry_lag_seconds").timer().count()).isEqualTo(1);
  }

  @Test
  void serverErrorSchedulesTheNextJitteredBackoff() {
    WebhookDelivery delivery = scheduleDelivery(UUID.randomUUID(), 3);
    when(client.attempt(anyString(), anyString(), anyString(), any(Duration.class)))
        .thenReturn(WebhookAttemptResult.httpError(503, "", null));

    scheduler.runOnce(NOW.plusSeconds(2));

    WebhookDelivery stored = store.allRows().get(0);
    assertThat(stored.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRYING);
    assertThat(stored.getAttempts()).isEqualTo(2);
    assertThat(stored.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(7));
    assertThat(stored.getLastStatusCode()).hasValue(503);
    assertThat(stored.getLeaseToken()).isEmpty();
    verify(nodePort, never())
        .nodeCompleted(
            any(), any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    assertThat(attemptsCount("retry")).isEqualTo(1.0);
  }

  @Test
  void exhaustedBudgetMarksDeadEmitsDlqAndIncrementsTheDeadMetric() {
    WebhookDelivery delivery = scheduleDelivery(UUID.randomUUID(), 2);
    when(client.attempt(anyString(), anyString(), anyString(), any(Duration.class)))
        .thenReturn(WebhookAttemptResult.httpError(500, "", null));

    scheduler.runOnce(NOW.plusSeconds(2));

    WebhookDelivery stored = store.allRows().get(0);
    assertThat(stored.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
    assertThat(stored.getAttempts()).isEqualTo(2);
    assertThat(stored.getLastStatusCode()).hasValue(500);
    verify(nodePort)
        .deliveryDead(
            delivery.getTenantId(),
            delivery.getWorkflowInstanceId(),
            delivery.getNodeExecutionId(),
            delivery.getId(),
            2,
            "Webhook target returned status 500.");
    assertThat(registry.get("awap.webhook.dead").counter().count()).isEqualTo(1.0);
    assertThat(attemptsCount("dead")).isEqualTo(1.0);
    assertThat(store.claimDue(NOW.plus(Duration.ofDays(1)), 10, Duration.ofSeconds(30))).isEmpty();
  }

  @Test
  void permanentClientErrorIsDeadAfterASingleRetry() {
    scheduleDelivery(UUID.randomUUID(), 5);
    when(client.attempt(anyString(), anyString(), anyString(), any(Duration.class)))
        .thenReturn(WebhookAttemptResult.httpError(404, "", null));

    scheduler.runOnce(NOW.plusSeconds(2));

    assertThat(store.allRows().get(0).getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
    assertThat(store.allRows().get(0).getAttempts()).isEqualTo(2);
    assertThat(registry.get("awap.webhook.dead").counter().count()).isEqualTo(1.0);
  }

  @Test
  void retryAfterHeaderOn429DelaysTheNextAttemptByAtLeastThatLong() {
    scheduleDelivery(UUID.randomUUID(), 4);
    when(client.attempt(anyString(), anyString(), anyString(), any(Duration.class)))
        .thenReturn(WebhookAttemptResult.httpError(429, "", "30"));

    scheduler.runOnce(NOW.plusSeconds(2));

    WebhookDelivery stored = store.allRows().get(0);
    assertThat(stored.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRYING);
    assertThat(stored.getNextAttemptAt()).isAfterOrEqualTo(NOW.plusSeconds(32));
  }

  @Test
  void transportFailureIsRetriedUntilTheBudgetIsExhausted() {
    scheduleDelivery(UUID.randomUUID(), 2);
    when(client.attempt(anyString(), anyString(), anyString(), any(Duration.class)))
        .thenReturn(WebhookAttemptResult.transportFailure("connect timed out"));

    scheduler.runOnce(NOW.plusSeconds(2));

    WebhookDelivery stored = store.allRows().get(0);
    assertThat(stored.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
    assertThat(stored.getLastError()).contains("connect timed out");
  }

  @Test
  void twoSchedulersClaimingConcurrentlySendExactlyOnce() throws Exception {
    scheduleDelivery(UUID.randomUUID(), 3);
    when(client.attempt(anyString(), anyString(), anyString(), any(Duration.class)))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));
    WebhookRetryScheduler secondScheduler = new WebhookRetryScheduler(service, runner);
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (WebhookRetryScheduler candidate : List.of(scheduler, secondScheduler)) {
        pool.submit(
            () -> {
              barrier.await(5, TimeUnit.SECONDS);
              return candidate.runOnce(NOW.plusSeconds(2));
            });
      }
      pool.shutdown();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    verify(client, times(1)).attempt(anyString(), anyString(), anyString(), any(Duration.class));
    verify(nodePort, times(1))
        .nodeCompleted(
            any(), any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    assertThat(attemptsCount("sent")).isEqualTo(1.0);
    assertThat(store.allRows().get(0).getAttempts()).isEqualTo(2);
  }

  @Test
  void anExpiredLeaseIsTakenOverAndOnlyTheNewOwnerCompletesTheNode() {
    WebhookDelivery delivery = scheduleDelivery(UUID.randomUUID(), 5);
    WebhookDelivery stale = store.claimDue(NOW.plusSeconds(2), 10, Duration.ofSeconds(5)).get(0);
    when(client.attempt(anyString(), anyString(), anyString(), any(Duration.class)))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));

    scheduler.runOnce(NOW.plusSeconds(60));

    assertThat(store.allRows().get(0).getStatus()).isEqualTo(WebhookDeliveryStatus.SENT);
    assertThat(store.allRows().get(0).getAttempts()).isEqualTo(3);
    verify(nodePort, times(1))
        .nodeCompleted(
            any(), any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt());

    // The stale owner still tries to record its outcome after losing the lease.
    runner.attempt(stale, NOW.plusSeconds(61));

    verify(nodePort, times(1))
        .nodeCompleted(
            any(), any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    assertThat(store.allRows().get(0).getStatus()).isEqualTo(WebhookDeliveryStatus.SENT);
    assertThat(store.allRows().get(0).getId()).isEqualTo(delivery.getId());
  }

  @Test
  void aPoisonRowDoesNotStopTheRestOfTheBatch() {
    scheduleDelivery(UUID.randomUUID(), 1);
    scheduleDelivery(UUID.randomUUID(), 1);
    when(client.attempt(anyString(), anyString(), anyString(), any(Duration.class)))
        .thenThrow(new IllegalStateException("client exploded"))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));

    int claimed = scheduler.runOnce(NOW.plusSeconds(2));

    assertThat(claimed).isEqualTo(2);
    verify(client, times(2)).attempt(anyString(), anyString(), anyString(), any(Duration.class));
    assertThat(store.allRows())
        .extracting(WebhookDelivery::getStatus)
        .containsExactlyInAnyOrder(WebhookDeliveryStatus.DEAD, WebhookDeliveryStatus.SENT);
  }

  @Test
  void leaseLostBeforeCompletionSkipsTheNodeCompletion() {
    scheduleDelivery(UUID.randomUUID(), 3);
    WebhookDelivery claimed = store.claimDue(NOW.plusSeconds(2), 10, Duration.ofSeconds(5)).get(0);
    // The lease is stolen by another replica before this attempt records its success.
    store.claimDue(NOW.plusSeconds(60), 10, Duration.ofSeconds(5));
    when(client.attempt(anyString(), anyString(), anyString(), any(Duration.class)))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));

    runner.attempt(claimed, NOW.plusSeconds(61));

    verify(nodePort, never())
        .nodeCompleted(
            any(), any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void noAttemptIsMadeWhenNothingIsDue() {
    scheduleDelivery(UUID.randomUUID(), 3);

    assertThat(scheduler.runOnce(NOW)).isZero();

    verify(client, never()).attempt(anyString(), anyString(), anyString(), any(Duration.class));
    assertThat(store.allRows().get(0).getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
  }
}
