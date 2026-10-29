package com.awap.notification.infrastructure;

import com.awap.notification.application.NotificationRetryClaim;
import com.awap.notification.application.NotificationRetryClaimRepository;
import com.awap.notification.domain.NotificationChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Claims due retries through the {@code awap_claim_notification_retries} SECURITY DEFINER helper:
 * the scheduler must see every tenant's rows while {@code awap_app} is confined by RLS, and the
 * helper's {@code FOR UPDATE SKIP LOCKED} plus lease token keep replicas apart.
 */
@Repository
public class JdbcNotificationRetryClaimRepository implements NotificationRetryClaimRepository {

  private static final String CLAIM_SQL =
      "SELECT id, tenant_id, channel, recipient, template_key, locale, variables_json,"
          + " attempts, status, dedupe_key, next_attempt_at, last_attempt_at"
          + " FROM awap_claim_notification_retries(?, ?, ?, ?, ?)";

  private final JdbcTemplate jdbcTemplate;

  public JdbcNotificationRetryClaimRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public List<NotificationRetryClaim> claimDue(
      Instant now, UUID leaseToken, Duration lease, int limit, int perTenantLimit) {
    long leaseSeconds = Math.max(1L, (lease.toMillis() + 999L) / 1000L);
    return jdbcTemplate.query(
        CLAIM_SQL,
        (rs, rowNum) ->
            new NotificationRetryClaim(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                NotificationChannel.valueOf(rs.getString("channel")),
                rs.getString("recipient"),
                rs.getString("template_key"),
                rs.getString("locale"),
                rs.getString("variables_json"),
                rs.getInt("attempts"),
                rs.getString("status"),
                rs.getString("dedupe_key"),
                toInstant(rs.getObject("next_attempt_at", OffsetDateTime.class)),
                toInstant(rs.getObject("last_attempt_at", OffsetDateTime.class))),
        OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
        leaseToken,
        (int) leaseSeconds,
        Math.max(1, limit),
        Math.max(1, perTenantLimit));
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
