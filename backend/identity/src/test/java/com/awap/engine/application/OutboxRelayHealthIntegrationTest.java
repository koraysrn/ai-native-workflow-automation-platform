package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.awap.engine.domain.OutboxEvent;
import com.awap.engine.infrastructure.OutboxEventRepository;
import com.awap.engine.infrastructure.OutboxRelayHealthIndicator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Runs the real relay (JPA fallback, H2) and asserts the actuator health indicator follows the
 * actual outcome of the publish attempts (issue #122, test plan 6.3 and 7.5).
 *
 * <p>The scheduled interval is pushed hours away so the relay only runs when the test calls it: a
 * background run would race the assertions.
 */
@SpringBootTest(
    classes = com.awap.identity.IdentityApplication.class,
    properties = {
      "awap.outbox.relay-interval-ms=3600000",
      "awap.outbox.relay-initial-delay-ms=3600000",
      "awap.outbox.datasource.enabled=false",
      "spring.datasource.url=jdbc:h2:mem:awap-outbox-health-it;DB_CLOSE_DELAY=-1"
    })
class OutboxRelayHealthIntegrationTest {

  @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private OutboxRelayService relayService;
  @Autowired private OutboxRelayHealthIndicator healthIndicator;
  @Autowired private OutboxEventRepository outboxRepository;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private OutboxRelayConfigurationGuard guard;

  private OutboxEvent newEvent(String eventType) {
    return outboxRepository.save(
        new OutboxEvent(UUID.randomUUID(), "WORKFLOW", UUID.randomUUID(), eventType, "{\"x\":1}"));
  }

  private static CompletableFuture<SendResult<String, String>> sent() {
    return CompletableFuture.completedFuture(new SendResult<>(null, null));
  }

  @Test
  void healthFollowsTheRealRelayRuns() {
    assertThat(healthIndicator.health().getStatus().getCode()).isEqualTo("UNKNOWN");
    assertThat(healthIndicator.health().getDetails())
        .containsEntry("mode", "jpa")
        .containsEntry("rlsSafe", false)
        .containsEntry("lastSuccessfulPublish", "none");

    OutboxEvent event = newEvent("TASK_COMPLETED");
    when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(sent());

    relayService.relayEvents();

    assertThat(outboxRepository.findById(event.getId()).orElseThrow().isPublished()).isTrue();
    assertThat(healthIndicator.health().getStatus().getCode()).isEqualTo("UP");
    assertThat(healthIndicator.health().getDetails().get("lastSuccessfulPublish"))
        .isNotEqualTo("none");
    assertThat(healthIndicator.health().getDetails()).containsEntry("publishedEvents", 1L);

    OutboxEvent failing = newEvent("TASK_COMPLETED");
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

    relayService.relayEvents();

    assertThat(outboxRepository.findById(failing.getId()).orElseThrow().isPublished()).isFalse();
    assertThat(healthIndicator.health().getStatus().getCode()).isEqualTo("DOWN");
    assertThat(healthIndicator.health().getDetails())
        .containsEntry("consecutiveFailures", 1)
        .containsEntry("lastFailureReason", "send_failed");

    when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(sent());

    relayService.relayEvents();

    assertThat(healthIndicator.health().getStatus().getCode()).isEqualTo("UP");
    assertThat(healthIndicator.health().getDetails()).containsEntry("consecutiveFailures", 0);
  }

  @Test
  void exposesTheActiveRelayModeAsAMetric() {
    assertThat(guard.mode()).isEqualTo(OutboxRelayMode.JPA);
    assertThat(
            meterRegistry
                .get(OutboxRelayMetrics.RELAY_MODE)
                .tag(OutboxRelayMetrics.MODE_TAG, "jpa")
                .gauge()
                .value())
        .isEqualTo(1.0d);
    assertThat(
            meterRegistry
                .get(OutboxRelayMetrics.RELAY_MODE)
                .tag(OutboxRelayMetrics.MODE_TAG, "jdbc")
                .gauge()
                .value())
        .isZero();
  }
}
