package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for the bounce/complaint suppression list (issue #204). Lookups are tenant-scoped so a
 * bounce recorded for one tenant can never silence another tenant's recipient.
 */
public interface NotificationSuppressionStore {

  /** Reason why the recipient is blocked, or empty when it is still deliverable. */
  Optional<String> suppressionReason(UUID tenantId, NotificationChannel channel, String recipient);

  /** Records a permanent bounce/complaint; idempotent for a repeated failure. */
  void suppress(UUID tenantId, NotificationChannel channel, String recipient, String reason);
}
