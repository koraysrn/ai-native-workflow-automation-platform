package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.application.SagaRecoveryCandidate;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Row mapping and lease argument handling of the SECURITY DEFINER recovery claim adapter. */
@ExtendWith(MockitoExtension.class)
class JdbcSagaRecoveryRepositoryTest {

  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private ResultSet resultSet;

  @Test
  void mapsTheClaimFunctionRowsAndClampsTheLease() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID sagaId = UUID.randomUUID();
    UUID leaseToken = UUID.randomUUID();
    when(resultSet.getObject("tenant_id", UUID.class)).thenReturn(tenantId);
    when(resultSet.getObject("saga_instance_id", UUID.class)).thenReturn(sagaId);
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              RowMapper<?> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(resultSet, 0));
            });

    List<SagaRecoveryCandidate> candidates =
        new JdbcSagaRecoveryRepository(jdbcTemplate)
            .claimDue(Instant.parse("2026-10-30T10:00:00Z"), leaseToken, Duration.ZERO, 0);

    assertThat(candidates).containsExactly(new SagaRecoveryCandidate(tenantId, sagaId, leaseToken));
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).query(anyString(), any(RowMapper.class), args.capture());
    assertThat(args.getValue()).contains(leaseToken, 1, 1);
  }
}
