package com.awap.usage.application;

import com.awap.usage.domain.UsageEvent;
import com.awap.usage.domain.UsageMetric;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** In-memory {@link UsageCounterStore} with the same idempotency and quota semantics as SQL. */
public class InMemoryUsageCounterStore implements UsageCounterStore {

  private final Set<String> eventKeys = new HashSet<>();
  private final Map<String, Long> counters = new LinkedHashMap<>();
  private final Map<UsageMetric, Long> limits = new LinkedHashMap<>();
  private final List<DriftRow> drifts = new ArrayList<>();
  private RuntimeException failure;
  private int archiveCalls;
  private LocalDate lastArchiveCutoff;

  @Override
  public synchronized IncrementResult increment(UsageEvent event, long limit) {
    if (failure != null) {
      throw failure;
    }
    String key = key(event.tenantId(), event.day(), event.metric());
    long current = counters.getOrDefault(key, 0L);
    if (!eventKeys.add(event.eventKey())) {
      return new IncrementResult(Outcome.DUPLICATE, current);
    }
    if (limit >= 0 && current + event.delta() > limit) {
      eventKeys.remove(event.eventKey());
      return new IncrementResult(Outcome.QUOTA_EXCEEDED, current);
    }
    long next = current + event.delta();
    counters.put(key, next);
    return new IncrementResult(Outcome.COUNTED, next);
  }

  @Override
  public synchronized List<CounterRow> counters(UUID tenantId, LocalDate from, LocalDate to) {
    if (failure != null) {
      throw failure;
    }
    List<CounterRow> rows = new ArrayList<>();
    for (Map.Entry<String, Long> entry : counters.entrySet()) {
      ParsedKey parsed = ParsedKey.parse(entry.getKey());
      if (parsed.tenantId().equals(tenantId)
          && !parsed.day().isBefore(from)
          && !parsed.day().isAfter(to)) {
        rows.add(new CounterRow(parsed.day(), parsed.metric(), entry.getValue()));
      }
    }
    return rows;
  }

  @Override
  public synchronized List<LimitRow> limits(UUID tenantId) {
    if (failure != null) {
      throw failure;
    }
    return limits.entrySet().stream().map(e -> new LimitRow(e.getKey(), e.getValue())).toList();
  }

  @Override
  public synchronized List<DriftRow> reconcile(LocalDate from, LocalDate to) {
    return List.copyOf(drifts);
  }

  @Override
  public synchronized int archiveBefore(LocalDate cutoff) {
    archiveCalls++;
    lastArchiveCutoff = cutoff;
    return 0;
  }

  public synchronized void setLimit(UsageMetric metric, long limit) {
    limits.put(metric, limit);
  }

  public synchronized void putCounter(
      UUID tenantId, LocalDate day, UsageMetric metric, long value) {
    counters.put(key(tenantId, day, metric), value);
  }

  public synchronized long value(UUID tenantId, LocalDate day, UsageMetric metric) {
    return counters.getOrDefault(key(tenantId, day, metric), 0L);
  }

  public synchronized void failWith(RuntimeException ex) {
    this.failure = ex;
  }

  public synchronized void clearFailure() {
    this.failure = null;
  }

  public synchronized void addDrift(DriftRow row) {
    drifts.add(row);
  }

  public synchronized int archiveCalls() {
    return archiveCalls;
  }

  public synchronized Optional<LocalDate> lastArchiveCutoff() {
    return Optional.ofNullable(lastArchiveCutoff);
  }

  private String key(UUID tenantId, LocalDate day, UsageMetric metric) {
    return tenantId + "|" + day + "|" + metric.name();
  }

  private record ParsedKey(UUID tenantId, LocalDate day, UsageMetric metric) {
    static ParsedKey parse(String key) {
      String[] parts = key.split("\\|");
      return new ParsedKey(
          UUID.fromString(parts[0]), LocalDate.parse(parts[1]), UsageMetric.valueOf(parts[2]));
    }
  }
}
