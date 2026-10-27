package com.awap.notification.infrastructure;

import com.awap.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.Collection;
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

  boolean existsByIdAndLeaseTokenAndStatusIn(UUID id, UUID leaseToken, Collection<String> statuses);

  /**
   * Records a successful attempt only while the caller still owns the lease. Returns 0 when another
   * replica won (SENT) or took the lease over, which prevents duplicate sends.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update NotificationDelivery d set d.status = 'SENT', d.attempts = :attempts,"
          + " d.lastError = null, d.lastAttemptAt = :now, d.nextAttemptAt = null,"
          + " d.leaseUntil = null, d.leaseToken = null, d.updatedAt = :now"
          + " where d.id = :id and d.leaseToken = :leaseToken"
          + " and d.status in ('PENDING', 'RETRYING')")
  int applySent(
      @Param("id") UUID id,
      @Param("leaseToken") UUID leaseToken,
      @Param("attempts") int attempts,
      @Param("now") Instant now);

  /** Schedules the next attempt and releases the lease. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update NotificationDelivery d set d.status = 'RETRYING', d.attempts = :attempts,"
          + " d.lastError = :error, d.lastAttemptAt = :lastAttemptAt,"
          + " d.nextAttemptAt = :nextAttemptAt, d.leaseUntil = null, d.leaseToken = null,"
          + " d.updatedAt = :lastAttemptAt"
          + " where d.id = :id and d.leaseToken = :leaseToken"
          + " and d.status in ('PENDING', 'RETRYING')")
  int applyRetrying(
      @Param("id") UUID id,
      @Param("leaseToken") UUID leaseToken,
      @Param("attempts") int attempts,
      @Param("error") String error,
      @Param("lastAttemptAt") Instant lastAttemptAt,
      @Param("nextAttemptAt") Instant nextAttemptAt);

  /** Terminal failure; the row is retained for audit/retention and never retried again. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update NotificationDelivery d set d.status = 'FAILED', d.attempts = :attempts,"
          + " d.lastError = :error, d.lastAttemptAt = :lastAttemptAt, d.nextAttemptAt = null,"
          + " d.leaseUntil = null, d.leaseToken = null, d.updatedAt = :lastAttemptAt"
          + " where d.id = :id and d.leaseToken = :leaseToken"
          + " and d.status in ('PENDING', 'RETRYING')")
  int applyFailed(
      @Param("id") UUID id,
      @Param("leaseToken") UUID leaseToken,
      @Param("attempts") int attempts,
      @Param("error") String error,
      @Param("lastAttemptAt") Instant lastAttemptAt);
}
