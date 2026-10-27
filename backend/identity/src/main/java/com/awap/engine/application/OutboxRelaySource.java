package com.awap.engine.application;

import java.util.List;
import java.util.UUID;

/**
 * Port for the outbox relay's read/release operations (issue #122).
 *
 * <p>Both relay paths implement this single contract so the relay loop, the failure accounting and
 * the observability surface are identical no matter whether the dedicated outbox pool (and its
 * {@code SECURITY DEFINER} functions) or the JPA fallback is active. Only the storage adapter and
 * the reported {@link #mode()} differ.
 */
public interface OutboxRelaySource {

  /** The path this adapter implements, reported by metrics and the health indicator. */
  OutboxRelayMode mode();

  /**
   * Claims up to {@code limit} unpublished rows so this relay run may publish them. A claimed row
   * is not yet published: a failed send is handed back through {@link #release(UUID)}.
   */
  List<PendingEvent> claimBatch(int limit);

  /** Returns a claimed row to the unpublished state after a failed publish. */
  void release(UUID id);

  /** Minimal projection of an outbox row that the relay is allowed to send. */
  record PendingEvent(UUID id, String eventType, UUID aggregateId, String payloadJson) {}
}
