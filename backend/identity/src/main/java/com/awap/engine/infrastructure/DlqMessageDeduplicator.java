package com.awap.engine.infrastructure;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Offset-based idempotency for the DLQ listener.
 *
 * <p>At-least-once delivery can hand the same record to the handler twice (rebalance, restart
 * before the offset commit). The state is bounded by the number of DLQ partitions, so a large
 * backlog cannot grow it without limit.
 */
@Component
public class DlqMessageDeduplicator {

  private final ConcurrentMap<String, AtomicLong> lastOffsetByPartition = new ConcurrentHashMap<>();

  /** Returns {@code true} exactly once per partition offset and {@code false} for a redelivery. */
  public boolean firstDelivery(String topic, int partition, long offset) {
    AtomicLong lastOffset =
        lastOffsetByPartition.computeIfAbsent(
            key(topic, partition), ignored -> new AtomicLong(-1L));
    while (true) {
      long previous = lastOffset.get();
      if (offset <= previous) {
        return false;
      }
      if (lastOffset.compareAndSet(previous, offset)) {
        return true;
      }
    }
  }

  /** Number of tracked topic-partitions, used by tests to prove the state is bounded. */
  public int trackedPartitions() {
    return lastOffsetByPartition.size();
  }

  private static String key(String topic, int partition) {
    return topic + "#" + partition;
  }
}
