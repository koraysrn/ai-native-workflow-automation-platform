package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DlqNotificationCorrelationTest {

  private final DlqNotificationCorrelation correlation =
      new DlqNotificationCorrelation(new ObjectMapper());

  @Test
  void recognisesOnlyTheNotificationDeadLetterTopic() {
    assertThat(correlation.isNotificationTopic("notification.send.dlq")).isTrue();
    assertThat(correlation.isNotificationTopic("workflow.task.events.dlq")).isFalse();
    assertThat(correlation.isNotificationTopic(null)).isFalse();
  }

  @Test
  void describesTheDeliveryRowFromThePayload() {
    String payload =
        "{\"deliveryId\":\"d-1\",\"dedupeKey\":\"k-1\",\"channel\":\"EMAIL\",\"attempts\":5,"
            + "\"reason\":\"EXHAUSTED\",\"recipient\":\"a@example.com\"}";

    Optional<String> description = correlation.describe("key", payload);

    assertThat(description)
        .contains("deliveryId=d-1 dedupeKey=k-1 channel=EMAIL attempts=5 reason=EXHAUSTED");
  }

  @Test
  void fallsBackToTheRecordKeyAndUnknownFields() {
    assertThat(correlation.describe("record-key", "{\"attempts\":\"x\"}").orElseThrow())
        .isEqualTo(
            "deliveryId=record-key dedupeKey=unknown channel=unknown attempts=0 reason=unknown");
    assertThat(correlation.describe(null, "{}").orElseThrow()).startsWith("deliveryId=unknown");
    assertThat(correlation.describe("  ", "{}").orElseThrow()).startsWith("deliveryId=unknown");
  }

  @Test
  void aNonObjectPayloadHasNoCorrelation() {
    assertThat(correlation.describe("key", "[1,2,3]")).isEmpty();
    assertThat(correlation.describe("key", "null")).isEmpty();
    assertThat(correlation.describe("key", null)).isEmpty();
    assertThat(correlation.describe("key", "   ")).isEmpty();
  }

  @Test
  void aMalformedPayloadHasNoCorrelation() {
    assertThat(correlation.describe("key", "{\"broken\":")).isEmpty();
  }

  @Test
  void loggingIsLimitedToTheNotificationTopic() {
    // Neither call may throw: the listener must keep running even for an odd payload.
    correlation.logCorrelation("workflow.task.events.dlq", 0, 1L, "key", "{\"ok\":true}");
    correlation.logCorrelation("notification.send.dlq", 0, 1L, "key", "{\"deliveryId\":\"d-1\"}");
    correlation.logCorrelation("notification.send.dlq", 0, 2L, "key", "{\"broken\":");
  }
}
