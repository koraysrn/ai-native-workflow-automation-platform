package com.awap.notification.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port for the cross-tenant retry claim. The database adapter uses {@code FOR UPDATE SKIP LOCKED}
 * and a lease token so concurrent replicas never claim the same row.
 */
public interface NotificationRetryClaimRepository {

  /**
   * Claims up to {@code limit} due rows, taking at most {@code perTenantLimit} rows per tenant.
   *
   * @param now current clock value (never earlier than the last attempt for a given row)
   * @param leaseToken unique token owned by this replica for this batch
   * @param lease how long the claim stays exclusive before another replica may take it over
   */
  List<NotificationRetryClaim> claimDue(
      Instant now, UUID leaseToken, Duration lease, int limit, int perTenantLimit);
}
