package com.awap.engine.application;

import com.awap.engine.domain.OutboxEvent;

/** Persists an outbox event durably before it is purged, preserving the audit trail. */
public interface OutboxEventArchiver {

  void archive(OutboxEvent event);
}
