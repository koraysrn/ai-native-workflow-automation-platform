package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.awap.engine.application.OutboxClaimStore;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PostgresOutboxClaimStoreTest {

  @Test
  @SuppressWarnings("unchecked")
  void mapsTheCorrelationIdReturnedByTheClaimFunction() throws Exception {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    ResultSet resultSet = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID claimToken = UUID.randomUUID();
    when(resultSet.getObject("id", UUID.class)).thenReturn(id);
    when(resultSet.getObject("aggregate_id", UUID.class)).thenReturn(aggregateId);
    when(resultSet.getObject("claim_token", UUID.class)).thenReturn(claimToken);
    when(resultSet.getString("event_type")).thenReturn("TASK_COMPLETED");
    when(resultSet.getString("payload_json")).thenReturn("{\"ok\":true}");
    when(resultSet.getString("correlation_id")).thenReturn("corr-1");
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              RowMapper<OutboxClaimStore.ClaimedOutboxEvent> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(resultSet, 0));
            });

    PostgresOutboxClaimStore store = new PostgresOutboxClaimStore(jdbcTemplate);

    List<OutboxClaimStore.ClaimedOutboxEvent> claimed =
        store.claimBatch(10, Duration.ofSeconds(60), 5);

    assertThat(claimed).hasSize(1);
    assertThat(claimed.get(0).id()).isEqualTo(id);
    assertThat(claimed.get(0).aggregateId()).isEqualTo(aggregateId);
    assertThat(claimed.get(0).claimToken()).isEqualTo(claimToken);
    assertThat(claimed.get(0).correlationId()).isEqualTo("corr-1");
  }
}
