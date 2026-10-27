package com.awap.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the notification template schema against real PostgreSQL: the unique template key, the
 * per-version audit uniqueness, tenant isolation through RLS and the built-in seed rows.
 *
 * <p>Migrations run in two steps so a tenant exists before V47 seeds the built-in templates.
 */
@Testcontainers(disabledWithoutDocker = true)
class NotificationTemplateITest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

  private static final UUID SEEDED_TENANT = UUID.randomUUID();
  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  @BeforeAll
  static void migrateAndSeed() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("37"))
        .load()
        .migrate();
    try (Connection admin = adminConnection();
        Statement stmt = admin.createStatement()) {
      stmt.executeUpdate(tenantInsert(SEEDED_TENANT, "seeded"));
    }
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    try (Connection admin = adminConnection();
        Statement stmt = admin.createStatement()) {
      stmt.executeUpdate(tenantInsert(TENANT_A, "tenant-a"));
      stmt.executeUpdate(tenantInsert(TENANT_B, "tenant-b"));
    }
  }

  @Test
  void migrationSeedsBuiltInTemplatesForExistingTenants() throws Exception {
    try (Connection app = appConnection(SEEDED_TENANT);
        Statement stmt = app.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT template_key, locale FROM notification_templates"
                    + " WHERE tenant_id = '"
                    + SEEDED_TENANT
                    + "' ORDER BY template_key, locale")) {
      List<String> rows = new ArrayList<>();
      while (rs.next()) {
        rows.add(rs.getString(1) + "/" + rs.getString(2));
      }
      assertThat(rows)
          .contains("approval-requested/en", "task-assigned/tr", "welcome/en", "welcome/tr")
          .hasSize(10);
    }
  }

  @Test
  void templateKeyIsUniquePerTenantAndLocale() throws Exception {
    UUID templateId = insertTemplate(TENANT_A, "invoice-sent", "en", 0);

    assertThatThrownBy(() -> insertTemplate(TENANT_A, "invoice-sent", "en", 0))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uq_notification_templates");
    assertThat(templateId).isNotNull();
  }

  @Test
  void templatesAreInvisibleToAnotherTenant() throws Exception {
    UUID templateId = insertTemplate(TENANT_A, "order-approved", "en", 0);

    try (Connection app = appConnection(TENANT_B);
        Statement stmt = app.createStatement()) {
      assertThat(
              countRows(
                  stmt,
                  "SELECT count(*) FROM notification_templates WHERE id = '" + templateId + "'"))
          .isZero();
      assertThat(
              stmt.executeUpdate(
                  "UPDATE notification_templates SET body = 'hacked' WHERE id = '"
                      + templateId
                      + "'"))
          .isZero();
    }
  }

  @Test
  void revisionsKeepAuditRowsAndRejectDuplicateVersions() throws Exception {
    UUID templateId = insertTemplate(TENANT_A, "payment-received", "en", 0);

    try (Connection app = appConnection(TENANT_A);
        Statement stmt = app.createStatement()) {
      stmt.executeUpdate(
          "INSERT INTO notification_template_versions"
              + " (id, template_id, tenant_id, version, subject, body, active, created_at)"
              + " VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + templateId
              + "', '"
              + TENANT_A
              + "', 0, 'Subject', 'Body', true, now())");

      assertThat(
              stmt.executeUpdate(
                  "UPDATE notification_templates SET body = 'Updated body', version = version + 1"
                      + " WHERE id = '"
                      + templateId
                      + "'"))
          .isEqualTo(1);

      assertThat(
              stmt.executeUpdate(
                  "INSERT INTO notification_template_versions"
                      + " (id, template_id, tenant_id, version, subject, body, active, created_at)"
                      + " VALUES ('"
                      + UUID.randomUUID()
                      + "', '"
                      + templateId
                      + "', '"
                      + TENANT_A
                      + "', 1, 'Subject', 'Updated body', true, now())"))
          .isEqualTo(1);
      assertThat(
              countRows(
                  stmt,
                  "SELECT count(*) FROM notification_template_versions WHERE template_id = '"
                      + templateId
                      + "'"))
          .isEqualTo(2);
    }

    assertThatThrownBy(
            () -> {
              try (Connection app = appConnection(TENANT_A);
                  Statement stmt = app.createStatement()) {
                stmt.executeUpdate(
                    "INSERT INTO notification_template_versions"
                        + " (id, template_id, tenant_id, version, subject, body, active, created_at)"
                        + " VALUES ('"
                        + UUID.randomUUID()
                        + "', '"
                        + templateId
                        + "', '"
                        + TENANT_A
                        + "', 1, 'Subject', 'Duplicate', true, now())");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uq_notification_template_versions");
  }

  @Test
  void oversizedBodyIsRejectedByTheCheckConstraint() {
    assertThatThrownBy(() -> insertTemplate(TENANT_B, "big-body", "en", 1_048_577))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_notification_templates_body_bytes");
  }

  private static UUID insertTemplate(UUID tenant, String key, String locale, int bodySize)
      throws SQLException {
    UUID id = UUID.randomUUID();
    String body = "a".repeat(bodySize);
    try (Connection admin = adminConnection();
        Statement stmt = admin.createStatement()) {
      stmt.executeUpdate(
          "INSERT INTO notification_templates"
              + " (id, tenant_id, template_key, locale, subject, body, version, active,"
              + " created_at, updated_at)"
              + " VALUES ('"
              + id
              + "', '"
              + tenant
              + "', '"
              + key
              + "', '"
              + locale
              + "', 'Subject', '"
              + body
              + "', 0, true, now(), now())");
    }
    return id;
  }

  private static long countRows(Statement stmt, String sql) throws SQLException {
    try (ResultSet rs = stmt.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection appConnection(UUID tenant) throws SQLException {
    Connection connection =
        DriverManager.getConnection(POSTGRES.getJdbcUrl(), "awap_app", "awap_app");
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + tenant + "'");
    }
    return connection;
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
}
