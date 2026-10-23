package com.awap.engine.application;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the partition scheduler invokes the V32 helper function. */
@ExtendWith(MockitoExtension.class)
class OutboxPartitionSchedulerTest {

  @Mock private JdbcTemplate jdbcTemplate;

  @Test
  void invokesMonthlyPartitionFunction() {
    OutboxPartitionScheduler scheduler = new OutboxPartitionScheduler(jdbcTemplate);

    scheduler.ensureCurrentMonthPartition();

    verify(jdbcTemplate)
        .execute("SELECT awap_ensure_outbox_monthly_partition(date_trunc('month', now())::date)");
  }
}
