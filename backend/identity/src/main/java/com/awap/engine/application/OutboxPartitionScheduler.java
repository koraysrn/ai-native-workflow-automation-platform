package com.awap.engine.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Creates the monthly outbox partition on the first day of each month (see V32). */
@Component
public class OutboxPartitionScheduler {

  private static final Logger log = LoggerFactory.getLogger(OutboxPartitionScheduler.class);

  private final JdbcTemplate jdbcTemplate;

  public OutboxPartitionScheduler(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Scheduled(cron = "0 0 2 1 * *")
  public void ensureCurrentMonthPartition() {
    jdbcTemplate.execute(
        "SELECT awap_ensure_outbox_monthly_partition(date_trunc('month', now())::date)");
    log.info("Ensured outbox partition for the current month.");
  }
}
