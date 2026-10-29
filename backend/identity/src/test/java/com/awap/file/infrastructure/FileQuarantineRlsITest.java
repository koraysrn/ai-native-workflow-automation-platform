package com.awap.file.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL RLS proof for file_records and the new file_scan_audit table (#118). */
@Testcontainers(disabledWithoutDocker = true)
class FileQuarantineRlsITest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();
  private static final UUID FILE_A = UUID.randomUUID();

  @BeforeAll
  static void migrateAndSeed() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    try (Connection admin = adminConnection();
        Statement stmt = admin.createStatement()) {
      stmt.executeUpdate(tenantInsert(TENANT_A, "file-tenant-a"));
      stmt.executeUpdate(tenantInsert(TENANT_B, "file-tenant-b"));
      stmt.executeUpdate(fileInsert(TENANT_A, FILE_A, "tenant-a-doc.pdf"));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to seed file records", ex);
    }
  }

  @Test
  void tenantSeesOnlyItsOwnFileRecords() throws Exception {
    assertThat(fileCount(TENANT_A)).isEqualTo(1);
    assertThat(fileCount(TENANT_B)).isZero();
  }

  @Test
  void crossTenantFileUpdateAffectsZeroRows() throws Exception {
    try (Connection app = appConnection();
        Statement stmt = app.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + TENANT_A + "'");
      int updated =
          stmt.executeUpdate(
              "UPDATE file_records SET status = 'PROMOTED' WHERE tenant_id = '" + TENANT_B + "'");
      assertThat(updated).isZero();
    }
  }

  @Test
  void auditRowsAreTenantScopedAndImmutableForOtherTenants() throws Exception {
    UUID auditId = UUID.randomUUID();
    try (Connection appA = appConnection();
        Statement stmt = appA.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + TENANT_A + "'");
      stmt.executeUpdate(
          "INSERT INTO file_scan_audit (id, tenant_id, file_id, event, detail, created_at)"
              + " VALUES ('"
              + auditId
              + "', '"
              + TENANT_A
              + "', '"
              + FILE_A
              + "', 'INFECTED', 'eicar', now())");
      try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM file_scan_audit")) {
        rs.next();
        assertThat(rs.getLong(1)).isEqualTo(1);
      }
    }

    try (Connection appB = appConnection();
        Statement stmt = appB.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + TENANT_B + "'");
      try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM file_scan_audit")) {
        rs.next();
        assertThat(rs.getLong(1)).isZero();
      }
      assertThatThrownBy(
              () ->
                  stmt.executeUpdate(
                      "INSERT INTO file_scan_audit (id, tenant_id, file_id, event, detail, created_at)"
                          + " VALUES ('"
                          + UUID.randomUUID()
                          + "', '"
                          + TENANT_A
                          + "', '"
                          + FILE_A
                          + "', 'INFECTED', 'forged', now())"))
          .isInstanceOf(java.sql.SQLException.class);
    }
  }

  private static long fileCount(UUID tenantId) throws Exception {
    try (Connection app = appConnection();
        Statement stmt = app.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + tenantId + "'");
      try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM file_records")) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static Connection adminConnection() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection appConnection() throws Exception {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "awap_app", "awap_app");
  }

  private static String tenantInsert(UUID id, String slug) {
    return "INSERT INTO tenants (id, slug, name, plan, max_users, max_workflows,"
        + " max_ai_calls_per_month, active, created_at, updated_at, deleted)"
        + " VALUES ('"
        + id
        + "', '"
        + slug
        + "', '"
        + slug
        + "', 'FREE', 10, 10, 1000, true, now(), now(), false)";
  }

  private static String fileInsert(UUID tenantId, UUID fileId, String name) {
    return "INSERT INTO file_records (id, tenant_id, name, size, bucket, object_key, status,"
        + " created_at) VALUES ('"
        + fileId
        + "', '"
        + tenantId
        + "', '"
        + name
        + "', 10, 'awap-quarantine', '"
        + tenantId
        + "/"
        + fileId
        + "/"
        + name
        + "', 'QUARANTINED', now())";
  }
}
