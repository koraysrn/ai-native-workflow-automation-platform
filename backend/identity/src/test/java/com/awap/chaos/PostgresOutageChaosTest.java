package com.awap.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.engine.application.OutboxClaimStore;
import com.awap.engine.infrastructure.PostgresOutboxClaimStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL outage/recovery fault injection (issue #211): a stopped database produces a bounded
 * error instead of hanging, and the same store drains the backlog once PostgreSQL is back.
 */
@Tag("chaos")
@Testcontainers(disabledWithoutDocker = true)
class PostgresOutageChaosTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

  private static JdbcTemplate admin;
  private static PostgresOutboxClaimStore store;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    // A short socket timeout keeps the paused-database query bounded instead of hanging the test.
    String appUrl = POSTGRES.getJdbcUrl() + "?connectTimeout=2&socketTimeout=2";
    store =
        new PostgresOutboxClaimStore(
            new JdbcTemplate(new DriverManagerDataSource(appUrl, "awap_app", "awap_app")));
  }

  private static UUID insertEvent(UUID tenantId) {
    UUID id = UUID.randomUUID();
    admin.update(
        "INSERT INTO outbox_events (id, tenant_id, aggregate_type, aggregate_id, event_type,"
            + " payload_json, created_at, published, publish_attempts)"
            + " VALUES (?, ?, 'TASK', ?, 'TASK_COMPLETED', '{\"ok\":true}', now(), false, 0)",
        id,
        tenantId,
        UUID.randomUUID());
    return id;
  }

  @Test
  void aDatabaseOutageFailsBoundedAndRecoversWithABacklogDrain() {
    UUID eventId = insertEvent(UUID.randomUUID());

    // Freeze the PostgreSQL process: existing connections cannot complete a query, while the
    // container (and its data directory) stays the same for the recovery phase.
    String containerId = POSTGRES.getContainerId();
    POSTGRES.getDockerClient().pauseContainerCmd(containerId).exec();
    Instant before = Instant.now();
    try {
      assertThatThrownBy(() -> store.claimBatch(10, Duration.ofSeconds(30), 5))
          .isInstanceOf(DataAccessException.class);
      assertThat(Duration.between(before, Instant.now())).isLessThan(Duration.ofSeconds(20));
    } finally {
      POSTGRES.getDockerClient().unpauseContainerCmd(containerId).exec();
    }

    // The same container and data directory are back; the backlog is claimed exactly as before.
    var claims = store.claimBatch(10, Duration.ofSeconds(30), 5);
    assertThat(claims).extracting(OutboxClaimStore.ClaimedOutboxEvent::id).contains(eventId);
  }
}
