package com.awap.notification.infrastructure;

import com.awap.notification.application.NotificationDeliveryRetentionRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Runs the cross-tenant purge helper; the scheduled purge has no tenant context to rely on. */
@Repository
public class JdbcNotificationDeliveryRetentionRepository
    implements NotificationDeliveryRetentionRepository {

  private static final String PURGE_SQL = "SELECT awap_purge_notification_deliveries(?, ?)";

  private final JdbcTemplate jdbcTemplate;

  public JdbcNotificationDeliveryRetentionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public long purge(Instant terminalCutoff, Instant retryCutoff) {
    Integer deleted =
        jdbcTemplate.queryForObject(
            PURGE_SQL,
            Integer.class,
            OffsetDateTime.ofInstant(terminalCutoff, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(retryCutoff, ZoneOffset.UTC));
    return deleted == null ? 0L : deleted.longValue();
  }
}
