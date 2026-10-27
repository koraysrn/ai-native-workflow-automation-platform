package com.awap.notification.infrastructure;

import com.awap.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for notification delivery records. */
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

  Optional<NotificationDelivery> findByTenantIdAndDedupeKey(UUID tenantId, String dedupeKey);

  List<NotificationDelivery> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  void deleteByCreatedAtBefore(Instant cutoff);

  /**
   * Purges terminal deliveries only after both the audit window and the de-duplication window have
   * elapsed, so a purge can never make an active dedupe key reusable.
   */
  void deleteByCreatedAtBeforeAndDedupeExpiresAtBefore(
      Instant createdAtCutoff, Instant dedupeCutoff);
}
