package com.awap.engine.infrastructure;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/** Retry backoff of 1s, 5s and 25s with up to 20% jitter, then STOP (routes to the DLQ). */
public class JitteredBackOff implements BackOff {

  private static final long[] INTERVALS = {1000L, 5000L, 25000L};

  @Override
  public BackOffExecution start() {
    return new Execution();
  }

  private static final class Execution implements BackOffExecution {

    private int index = 0;

    @Override
    public long nextBackOff() {
      if (index >= INTERVALS.length) {
        return STOP;
      }
      long base = INTERVALS[index++];
      return base + (long) (base * 0.2 * Math.random());
    }
  }
}
