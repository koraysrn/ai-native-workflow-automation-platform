package com.awap.notification.application;

import java.time.Instant;

/** Cross-tenant retention port implemented by a SECURITY DEFINER helper. */
public interface NotificationDeliveryRetentionRepository {

  /**
   * Deletes delivered/permanently failed rows older than {@code terminalCutoff} and retry rows
   * older than {@code retryCutoff}.
   *
   * @return the number of rows removed
   */
  long purge(Instant terminalCutoff, Instant retryCutoff);
}
