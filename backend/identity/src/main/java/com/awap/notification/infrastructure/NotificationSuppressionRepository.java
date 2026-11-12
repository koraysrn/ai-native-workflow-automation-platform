package com.awap.notification.infrastructure;

import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationSuppression;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for the bounce/complaint suppression list (issue #204). */
public interface NotificationSuppressionRepository
    extends JpaRepository<NotificationSuppression, UUID> {

  Optional<NotificationSuppression> findByTenantIdAndChannelAndRecipient(
      UUID tenantId, NotificationChannel channel, String recipient);

  boolean existsByTenantIdAndChannelAndRecipient(
      UUID tenantId, NotificationChannel channel, String recipient);
}
