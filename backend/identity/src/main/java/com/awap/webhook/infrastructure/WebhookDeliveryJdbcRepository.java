package com.awap.webhook.infrastructure;

import com.awap.webhook.application.WebhookDeliveryStore;
import com.awap.webhook.domain.WebhookDelivery;
import com.awap.webhook.domain.WebhookDeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL adapter for the durable webhook queue.
 *
 * <p>Claims go through the SECURITY DEFINER helper {@code awap_claim_due_webhook_deliveries}
 * ({@code FOR UPDATE SKIP LOCKED} + lease token) so replicas never share a row. Every tenant-scoped
 * statement sets {@code awap.tenant_id} for RLS and filters {@code tenant_id} explicitly.
 */
@Repository
public class WebhookDeliveryJdbcRepository implements WebhookDeliveryStore {

  private static final String COLUMNS =
      "id, tenant_id, workflow_instance_id, node_execution_id, node_id, target_url, payload_json,"
          + " idempotency_key, status, attempts, max_attempts, next_attempt_at, lease_until,"
          + " lease_token, last_status_code, last_error, created_at, updated_at, expires_at";

  private static final RowMapper<WebhookDelivery> ROW_MAPPER =
      (rs, rowNum) ->
          WebhookDelivery.rehydrate(
              rs.getObject("id", UUID.class),
              rs.getObject("tenant_id", UUID.class),
              rs.getObject("workflow_instance_id", UUID.class),
              rs.getObject("node_execution_id", UUID.class),
              rs.getString("node_id"),
              rs.getString("target_url"),
              rs.getString("payload_json"),
              rs.getString("idempotency_key"),
              WebhookDeliveryStatus.valueOf(rs.getString("status")),
              rs.getInt("attempts"),
              rs.getInt("max_attempts"),
              instant(rs.getObject("next_attempt_at", OffsetDateTime.class)),
              nullableInstant(rs.getObject("lease_until", OffsetDateTime.class)),
              rs.getString("lease_token"),
              rs.getObject("last_status_code", Integer.class),
              rs.getString("last_error"),
              instant(rs.getObject("created_at", OffsetDateTime.class)),
              instant(rs.getObject("updated_at", OffsetDateTime.class)),
              instant(rs.getObject("expires_at", OffsetDateTime.class)));

  private final JdbcTemplate jdbcTemplate;

  public WebhookDeliveryJdbcRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public WebhookDelivery save(WebhookDelivery delivery) {
    setTenant(delivery.getTenantId());
    jdbcTemplate.update(
        "INSERT INTO webhook_deliveries (id, tenant_id, workflow_instance_id, node_execution_id,"
            + " node_id, target_url, payload_json, idempotency_key, status, attempts, max_attempts,"
            + " next_attempt_at, lease_until, lease_token, last_status_code, last_error, created_at,"
            + " updated_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " ?)",
        delivery.getId(),
        delivery.getTenantId(),
        delivery.getWorkflowInstanceId(),
        delivery.getNodeExecutionId(),
        delivery.getNodeId(),
        delivery.getTargetUrl(),
        delivery.getPayloadJson(),
        delivery.getIdempotencyKey(),
        delivery.getStatus().name(),
        delivery.getAttempts(),
        delivery.getMaxAttempts(),
        ts(delivery.getNextAttemptAt()),
        delivery.getLeaseUntil().map(WebhookDeliveryJdbcRepository::ts).orElse(null),
        delivery.getLeaseToken().orElse(null),
        delivery.getLastStatusCode().isPresent() ? delivery.getLastStatusCode().getAsInt() : null,
        delivery.getLastError().orElse(null),
        ts(delivery.getCreatedAt()),
        ts(delivery.getUpdatedAt()),
        ts(delivery.getExpiresAt()));
    return delivery;
  }

  @Override
  public Optional<WebhookDelivery> findByTenantIdAndIdempotencyKey(
      UUID tenantId, String idempotencyKey) {
    setTenant(tenantId);
    return jdbcTemplate
        .query(
            "SELECT "
                + COLUMNS
                + " FROM webhook_deliveries WHERE tenant_id = ? AND idempotency_key = ?",
            ROW_MAPPER,
            tenantId,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  @Override
  public List<WebhookDelivery> claimDue(Instant now, int limit, Duration leaseDuration) {
    return jdbcTemplate.query(
        "SELECT * FROM awap_claim_due_webhook_deliveries(?, ?, ?)",
        ROW_MAPPER,
        ts(now),
        Math.max(1, limit),
        Math.max(1, (int) leaseDuration.toSeconds()));
  }

  @Override
  public boolean recordSent(
      UUID id, UUID tenantId, String leaseToken, int statusCode, Instant now) {
    setTenant(tenantId);
    return jdbcTemplate.update(
            "UPDATE webhook_deliveries SET status = 'SENT', last_status_code = ?, last_error = NULL,"
                + " next_attempt_at = ?, lease_token = NULL, lease_until = NULL, updated_at = ?"
                + " WHERE id = ? AND tenant_id = ? AND lease_token = ? AND status = 'RETRYING'",
            statusCode,
            ts(now),
            ts(now),
            id,
            tenantId,
            leaseToken)
        == 1;
  }

  @Override
  public boolean recordRetry(
      UUID id,
      UUID tenantId,
      String leaseToken,
      int statusCode,
      String error,
      Instant nextAttemptAt,
      Instant now) {
    setTenant(tenantId);
    return jdbcTemplate.update(
            "UPDATE webhook_deliveries SET status = 'RETRYING', last_status_code = ?, last_error = ?,"
                + " next_attempt_at = ?, lease_token = NULL, lease_until = NULL, updated_at = ?"
                + " WHERE id = ? AND tenant_id = ? AND lease_token = ? AND status = 'RETRYING'",
            statusCode,
            error,
            ts(nextAttemptAt),
            ts(now),
            id,
            tenantId,
            leaseToken)
        == 1;
  }

  @Override
  public boolean recordDead(
      UUID id, UUID tenantId, String leaseToken, int statusCode, String error, Instant now) {
    setTenant(tenantId);
    return jdbcTemplate.update(
            "UPDATE webhook_deliveries SET status = 'DEAD', last_status_code = ?, last_error = ?,"
                + " next_attempt_at = ?, lease_token = NULL, lease_until = NULL, updated_at = ?"
                + " WHERE id = ? AND tenant_id = ? AND lease_token = ? AND status = 'RETRYING'",
            statusCode,
            error,
            ts(now),
            ts(now),
            id,
            tenantId,
            leaseToken)
        == 1;
  }

  @Override
  public List<WebhookDelivery> findByTenant(
      UUID tenantId, Optional<WebhookDeliveryStatus> status, int limit) {
    setTenant(tenantId);
    if (status.isPresent()) {
      return jdbcTemplate.query(
          "SELECT "
              + COLUMNS
              + " FROM webhook_deliveries WHERE tenant_id = ? AND status = ?"
              + " ORDER BY created_at DESC LIMIT ?",
          ROW_MAPPER,
          tenantId,
          status.get().name(),
          limit);
    }
    return jdbcTemplate.query(
        "SELECT "
            + COLUMNS
            + " FROM webhook_deliveries WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
        ROW_MAPPER,
        tenantId,
        limit);
  }

  @Override
  public int cancelByInstance(UUID tenantId, UUID instanceId, Instant now) {
    setTenant(tenantId);
    return jdbcTemplate.update(
        "UPDATE webhook_deliveries SET status = 'CANCELLED', next_attempt_at = ?,"
            + " lease_token = NULL, lease_until = NULL, updated_at = ?"
            + " WHERE tenant_id = ? AND workflow_instance_id = ?"
            + " AND status IN ('PENDING', 'RETRYING')",
        ts(now),
        ts(now),
        tenantId,
        instanceId);
  }

  @Override
  public int cancelExpired(Instant now) {
    // Not tenant scoped by design: a scheduled maintenance sweep across the whole queue.
    return jdbcTemplate.update(
        "UPDATE webhook_deliveries SET status = 'CANCELLED', next_attempt_at = ?,"
            + " lease_token = NULL, lease_until = NULL, updated_at = ?"
            + " WHERE status IN ('PENDING', 'RETRYING') AND expires_at <= ?"
            + " AND (lease_until IS NULL OR lease_until <= ?)",
        ts(now),
        ts(now),
        ts(now),
        ts(now));
  }

  private void setTenant(UUID tenantId) {
    jdbcTemplate.queryForObject(
        "SELECT set_config('awap.tenant_id', ?, true)", String.class, tenantId.toString());
  }

  private static OffsetDateTime ts(Instant instant) {
    return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value.toInstant();
  }

  private static Instant nullableInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
