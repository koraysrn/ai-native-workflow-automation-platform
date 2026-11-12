package com.awap.notification.infrastructure;

import com.awap.notification.application.NotificationSuppressionStore;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationSuppression;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** Tenant-scoped suppression store backed by {@code notification_suppressions} (issue #204). */
@Component
public class JpaNotificationSuppressionStore implements NotificationSuppressionStore {

  private static final Logger log = LoggerFactory.getLogger(JpaNotificationSuppressionStore.class);

  private final NotificationSuppressionRepository repository;

  public JpaNotificationSuppressionStore(NotificationSuppressionRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<String> suppressionReason(
      UUID tenantId, NotificationChannel channel, String recipient) {
    return repository
        .findByTenantIdAndChannelAndRecipient(tenantId, channel, recipient)
        .map(NotificationSuppression::getReason);
  }

  @Override
  public void suppress(
      UUID tenantId, NotificationChannel channel, String recipient, String reason) {
    if (repository.existsByTenantIdAndChannelAndRecipient(tenantId, channel, recipient)) {
      return;
    }
    try {
      repository.save(new NotificationSuppression(tenantId, channel, recipient, reason));
    } catch (DataIntegrityViolationException ex) {
      // A concurrent bounce recorded the same recipient; the unique constraint wins.
      log.debug("Suppression for channel={} was already recorded.", channel);
    }
  }
}
