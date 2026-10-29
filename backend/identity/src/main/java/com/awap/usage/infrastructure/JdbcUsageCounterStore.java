package com.awap.usage.infrastructure;

import com.awap.usage.application.UsageCounterStore;
import com.awap.usage.domain.TenantUsageLimit;
import com.awap.usage.domain.UsageCounter;
import com.awap.usage.domain.UsageEvent;
import com.awap.usage.domain.UsageMetric;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL adapter. Increments run inside {@code awap_usage_record_event}: a single conditional
 * UPDATE plus a unique event-key insert, which is what makes duplicate delivery harmless and keeps
 * the last allowed increment exactly on the quota boundary.
 */
@Component
public class JdbcUsageCounterStore implements UsageCounterStore {

  private static final long DUPLICATE = -2L;
  private static final long QUOTA_EXCEEDED = -1L;

  private final JdbcTemplate jdbcTemplate;
  private final UsageCounterRepository counterRepository;
  private final TenantUsageLimitRepository limitRepository;

  public JdbcUsageCounterStore(
      JdbcTemplate jdbcTemplate,
      UsageCounterRepository counterRepository,
      TenantUsageLimitRepository limitRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.counterRepository = counterRepository;
    this.limitRepository = limitRepository;
  }

  @Override
  public IncrementResult increment(UsageEvent event, long limit) {
    Long result =
        jdbcTemplate.queryForObject(
            "SELECT awap_usage_record_event(?, ?, ?, ?, ?, ?)",
            Long.class,
            event.eventKey(),
            event.tenantId(),
            Date.valueOf(event.day()),
            event.metric().name(),
            event.delta(),
            limit);

    long value = result == null ? QUOTA_EXCEEDED : result;
    if (value == DUPLICATE) {
      return new IncrementResult(Outcome.DUPLICATE, 0L);
    }
    if (value == QUOTA_EXCEEDED) {
      return new IncrementResult(Outcome.QUOTA_EXCEEDED, 0L);
    }
    return new IncrementResult(Outcome.COUNTED, value);
  }

  @Override
  public List<CounterRow> counters(UUID tenantId, LocalDate from, LocalDate to) {
    return counterRepository.findUsage(tenantId, from, to).stream().map(this::toRow).toList();
  }

  @Override
  public List<LimitRow> limits(UUID tenantId) {
    return limitRepository.findLimits(tenantId).stream().map(this::toLimitRow).toList();
  }

  @Override
  public List<DriftRow> reconcile(LocalDate from, LocalDate to) {
    return jdbcTemplate.query(
        "SELECT * FROM awap_usage_reconcile(?, ?)",
        (rs, index) ->
            new DriftRow(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("day", LocalDate.class),
                UsageMetric.valueOf(rs.getString("metric")),
                rs.getLong("expected"),
                rs.getLong("actual")),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  @Override
  public int archiveBefore(LocalDate cutoff) {
    Integer archived =
        jdbcTemplate.queryForObject(
            "SELECT awap_usage_archive(?)", Integer.class, Date.valueOf(cutoff));
    return archived == null ? 0 : archived;
  }

  private CounterRow toRow(UsageCounter counter) {
    return new CounterRow(
        counter.getDay(), UsageMetric.valueOf(counter.getMetric()), counter.getValue());
  }

  private LimitRow toLimitRow(TenantUsageLimit limit) {
    return new LimitRow(UsageMetric.valueOf(limit.getMetric()), limit.getDailyLimit());
  }
}
