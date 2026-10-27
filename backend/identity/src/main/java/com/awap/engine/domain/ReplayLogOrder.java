package com.awap.engine.domain;

import java.util.Comparator;

/**
 * Deterministic replay ordering: {@code createdAt} first and the log id as tie-breaker, so two rows
 * written in the same millisecond still fold in a stable order.
 */
public final class ReplayLogOrder implements Comparator<ReplayLogEntry> {

  public static final ReplayLogOrder INSTANCE = new ReplayLogOrder();

  private ReplayLogOrder() {}

  @Override
  public int compare(ReplayLogEntry left, ReplayLogEntry right) {
    int byInstant = left.createdAt().compareTo(right.createdAt());
    return byInstant != 0 ? byInstant : left.id().compareTo(right.id());
  }
}
