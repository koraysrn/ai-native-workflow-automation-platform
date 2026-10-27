package com.awap.notification.infrastructure;

import com.awap.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for notification delivery records. */
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

  Optional<NotificationDelivery> findByTenantIdAndDedupeKey(UUID tenantId, String dedupeKey);

  List<NotificationDelivery> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  List<NotificationDelivery> findByTenantIdAndReadAtIsNullOrderByCreatedAtDesc(UUID tenantId);

  long countByTenantIdAndReadAtIsNull(UUID tenantId);

  /**
   * Acknowledges a notification only while it is still unread, so concurrent read callers cannot
   * move the retention clock. Returns the number of rows changed (0 or 1).
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update NotificationDelivery d"
          + " set d.readAt = :at, d.updatedAt = :at, d.version = d.version + 1"
          + " where d.id = :id and d.tenantId = :tenantId and d.readAt is null")
  int markRead(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("at") Instant at);

  /**
   * Restores the unread state only while the notification is read. Returns the number of rows
   * changed (0 or 1).
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update NotificationDelivery d"
          + " set d.readAt = null, d.updatedAt = :at, d.version = d.version + 1"
          + " where d.id = :id and d.tenantId = :tenantId and d.readAt is not null")
  int markUnread(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("at") Instant at);

  /** Purges read deliveries whose acknowledgement is older than the cutoff. */
  @Query(value = "SELECT awap_purge_read_notifications(:cutoff)", nativeQuery = true)
  int purgeReadBefore(@Param("cutoff") OffsetDateTime cutoff);

  /** Purges notifications that were never acknowledged and are older than the cutoff. */
  @Query(value = "SELECT awap_purge_unread_notifications(:cutoff)", nativeQuery = true)
  int purgeUnreadBefore(@Param("cutoff") OffsetDateTime cutoff);
}
