package com.awap.engine.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Correlates a notification dead letter with its delivery row (issue #205).
 *
 * <p>The failure payload that {@code NotificationService}/{@code NotificationRetryWorker} write to
 * the outbox carries the delivery id, the de-duplication key, the channel, the attempt count and
 * the permanent-failure reason. The DLQ consumer does not republish anything, so the only thing it
 * must do is make the record traceable: every {@code notification.send.dlq} record is logged with
 * those fields plus its own topic/partition/offset, which is exactly what the replay tool needs to
 * find the record again and to prove that a resend cannot duplicate an already-sent delivery.
 */
@Component
public class DlqNotificationCorrelation {

  /** The dedicated notification dead-letter topic (mapped by {@code OutboxRelayService}). */
  public static final String TOPIC = "notification.send.dlq";

  private static final Logger log = LoggerFactory.getLogger(DlqNotificationCorrelation.class);

  private final ObjectMapper objectMapper;

  public DlqNotificationCorrelation(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** True for the notification dead-letter topic only. */
  public boolean isNotificationTopic(String topic) {
    return TOPIC.equals(topic);
  }

  /**
   * Builds the correlation description from the record key (the delivery id used as the Kafka key)
   * and the payload. Empty when the payload is not a JSON object; such a record is quarantined by
   * {@link DlqMessageHandler} instead.
   */
  public Optional<String> describe(String recordKey, String payload) {
    if (payload == null || payload.isBlank()) {
      return Optional.empty();
    }
    JsonNode node;
    try {
      node = objectMapper.readTree(payload);
    } catch (JsonProcessingException ex) {
      return Optional.empty();
    }
    if (!node.isObject()) {
      return Optional.empty();
    }
    String deliveryId = node.path("deliveryId").asText(fallbackId(recordKey));
    return Optional.of(
        "deliveryId="
            + deliveryId
            + " dedupeKey="
            + node.path("dedupeKey").asText("unknown")
            + " channel="
            + node.path("channel").asText("unknown")
            + " attempts="
            + node.path("attempts").asInt(0)
            + " reason="
            + node.path("reason").asText("unknown"));
  }

  /**
   * Logs the correlation for a notification dead letter. Records on any other topic are ignored so
   * the existing dead-letter logging stays unchanged for them.
   */
  public void logCorrelation(
      String topic, int partition, long offset, String recordKey, String payload) {
    if (!isNotificationTopic(topic)) {
      return;
    }
    describe(recordKey, payload)
        .ifPresent(
            description ->
                log.warn(
                    "Notification dead letter {} dlqTopic={} partition={} offset={}",
                    description,
                    topic,
                    partition,
                    offset));
  }

  private static String fallbackId(String recordKey) {
    return recordKey == null || recordKey.isBlank() ? "unknown" : recordKey;
  }
}
