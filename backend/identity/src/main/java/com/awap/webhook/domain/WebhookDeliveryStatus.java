package com.awap.webhook.domain;

/**
 * Durable delivery lifecycle.
 *
 * <pre>
 *   PENDING ──claim──▶ RETRYING ──success──▶ SENT
 *      │                  │
 *      ├──permanent───────┼──▶ DEAD
 *      └──cancel──────────┴──▶ CANCELLED
 * </pre>
 *
 * {@code PENDING} is the state right after the synchronous first attempt failed and a retry was
 * scheduled; {@code RETRYING} means a scheduler replica currently owns a lease for the row.
 */
public enum WebhookDeliveryStatus {
  PENDING,
  RETRYING,
  SENT,
  DEAD,
  CANCELLED;

  /** Rows in this state can still be claimed for an HTTP attempt. */
  public boolean isClaimable() {
    return this == PENDING || this == RETRYING;
  }
}
