package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory implementation of the claim and state ports. It reproduces the SQL semantics that
 * matter for the retry state machine: due filtering, per-tenant caps, lease ownership and the
 * status guard on every write.
 */
final class InMemoryNotificationDeliveryStore
    implements NotificationRetryClaimRepository, NotificationDeliveryStateStore {

  static final class Row {
    final UUID id;
    final UUID tenantId;
    final NotificationChannel channel;
    final String recipient;
    final String templateKey;
    final String locale;
    final String variablesJson;
    final String dedupeKey;
    Instant nextAttemptAt;
    Instant lastAttemptAt;
    int attempts;
    String status;
    String lastError;
    Instant leaseUntil;
    UUID leaseToken;

    private Row(
        UUID tenantId,
        NotificationChannel channel,
        String recipient,
        String templateKey,
        String locale,
        String variablesJson,
        int attempts,
        String status,
        Instant nextAttemptAt) {
      this.id = UUID.randomUUID();
      this.tenantId = tenantId;
      this.channel = channel;
      this.recipient = recipient;
      this.templateKey = templateKey;
      this.locale = locale;
      this.variablesJson = variablesJson;
      this.dedupeKey = "dedupe-" + id;
      this.attempts = attempts;
      this.status = status;
      this.nextAttemptAt = nextAttemptAt;
      this.lastAttemptAt = status.equals("RETRYING") ? nextAttemptAt : null;
    }
  }

  private final Map<UUID, Row> rows = new LinkedHashMap<>();

  synchronized UUID addDueRow(
      UUID tenantId,
      NotificationChannel channel,
      String recipient,
      Instant dueAt,
      int attempts,
      String status) {
    Row row =
        new Row(
            tenantId,
            channel,
            recipient,
            "welcome",
            "en",
            "{\"name\":\"Ada\"}",
            attempts,
            status,
            dueAt);
    rows.put(row.id, row);
    return row.id;
  }

  synchronized UUID addSentRow(UUID tenantId, String recipient, Instant dueAt) {
    Row row =
        new Row(
            tenantId,
            NotificationChannel.EMAIL,
            recipient,
            "welcome",
            "en",
            null,
            1,
            "SENT",
            dueAt);
    rows.put(row.id, row);
    return row.id;
  }

  synchronized Row row(UUID id) {
    return rows.get(id);
  }

  @Override
  public synchronized List<NotificationRetryClaim> claimDue(
      Instant now, UUID leaseToken, Duration lease, int limit, int perTenantLimit) {
    List<Row> due =
        rows.values().stream()
            .filter(row -> "PENDING".equals(row.status) || "RETRYING".equals(row.status))
            .filter(row -> row.nextAttemptAt != null && !row.nextAttemptAt.isAfter(now))
            .filter(row -> row.leaseUntil == null || row.leaseUntil.isBefore(now))
            .sorted(Comparator.comparing(row -> row.nextAttemptAt))
            .toList();

    Map<UUID, Integer> perTenant = new HashMap<>();
    List<NotificationRetryClaim> claims = new ArrayList<>();
    for (Row row : due) {
      int used = perTenant.getOrDefault(row.tenantId, 0);
      if (used >= perTenantLimit) {
        continue;
      }
      perTenant.put(row.tenantId, used + 1);
      row.leaseToken = leaseToken;
      row.leaseUntil = now.plus(lease);
      claims.add(
          new NotificationRetryClaim(
              row.id,
              row.tenantId,
              row.channel,
              row.recipient,
              row.templateKey,
              row.locale,
              row.variablesJson,
              row.attempts,
              row.status,
              row.dedupeKey,
              row.nextAttemptAt,
              row.lastAttemptAt));
      if (claims.size() >= limit) {
        break;
      }
    }
    return claims;
  }

  @Override
  public synchronized boolean isLeaseCurrent(UUID deliveryId, UUID leaseToken) {
    Row row = rows.get(deliveryId);
    return row != null
        && leaseToken != null
        && leaseToken.equals(row.leaseToken)
        && ("PENDING".equals(row.status) || "RETRYING".equals(row.status));
  }

  @Override
  public synchronized boolean markSent(
      UUID deliveryId, UUID leaseToken, int attempts, Instant now) {
    Row row = rows.get(deliveryId);
    if (row == null || !isRetriableAndLeased(row, leaseToken)) {
      return false;
    }
    row.status = "SENT";
    row.attempts = attempts;
    row.lastAttemptAt = now;
    row.nextAttemptAt = null;
    row.lastError = null;
    row.leaseToken = null;
    row.leaseUntil = null;
    return true;
  }

  @Override
  public synchronized boolean markRetrying(
      UUID deliveryId,
      UUID leaseToken,
      int attempts,
      String error,
      Instant lastAttemptAt,
      Instant nextAttemptAt) {
    Row row = rows.get(deliveryId);
    if (row == null || !isRetriableAndLeased(row, leaseToken)) {
      return false;
    }
    row.status = "RETRYING";
    row.attempts = attempts;
    row.lastAttemptAt = lastAttemptAt;
    row.nextAttemptAt = nextAttemptAt;
    row.lastError = error;
    row.leaseToken = null;
    row.leaseUntil = null;
    return true;
  }

  @Override
  public synchronized boolean markFailedPermanently(
      UUID deliveryId, UUID leaseToken, int attempts, String error, Instant lastAttemptAt) {
    Row row = rows.get(deliveryId);
    if (row == null || !isRetriableAndLeased(row, leaseToken)) {
      return false;
    }
    row.status = "FAILED";
    row.attempts = attempts;
    row.lastAttemptAt = lastAttemptAt;
    row.nextAttemptAt = null;
    row.lastError = error;
    row.leaseToken = null;
    row.leaseUntil = null;
    return true;
  }

  private static boolean isRetriableAndLeased(Row row, UUID leaseToken) {
    return leaseToken != null
        && leaseToken.equals(row.leaseToken)
        && ("PENDING".equals(row.status) || "RETRYING".equals(row.status));
  }
}
