package com.awap.usage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.IdentityApplication;
import com.awap.identity.domain.Tenant;
import com.awap.identity.infrastructure.TenantRepository;
import com.awap.usage.application.UsageMeteringService;
import com.awap.usage.application.UsageQueryService;
import com.awap.usage.application.UsageReconciliationService;
import com.awap.usage.domain.TenantUsageLimit;
import com.awap.usage.domain.UsageMetric;
import com.awap.usage.interfaces.dto.UsageMetricResponse;
import com.awap.usage.interfaces.dto.UsageResponse;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Wires the real adapters (repositories, SQL functions, service) against PostgreSQL so the read
 * model and the atomic write path are exercised together, not just against fakes.
 */
@SpringBootTest(classes = IdentityApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class UsageMeteringPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
  }

  @Autowired private UsageMeteringService meteringService;
  @Autowired private UsageQueryService queryService;
  @Autowired private UsageReconciliationService reconciliationService;
  @Autowired private UsageCounterRepository counterRepository;
  @Autowired private TenantUsageLimitRepository limitRepository;
  @Autowired private TenantRepository tenantRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void recordIsIdempotentAndVisibleThroughTheRepository() {
    Tenant tenant = tenant("usage-record");

    assertThat(meteringService.record(tenant.getId(), UsageMetric.WORKFLOW_EXECUTION, 1L, "pg-dup"))
        .isTrue();
    assertThat(meteringService.record(tenant.getId(), UsageMetric.WORKFLOW_EXECUTION, 1L, "pg-dup"))
        .isFalse();

    LocalDate today = meteringService.today();
    assertThat(counterRepository.findUsage(tenant.getId(), today, today))
        .singleElement()
        .satisfies(
            counter -> {
              assertThat(counter.getMetric()).isEqualTo("WORKFLOW_EXECUTION");
              assertThat(counter.getValue()).isEqualTo(1L);
            });
  }

  @Test
  void reserveAllowsExactlyTheTenantDailyLimit() {
    Tenant tenant = tenant("usage-quota");
    limitRepository.save(new TenantUsageLimit(tenant.getId(), UsageMetric.WORKFLOW_EXECUTION, 2L));

    meteringService.reserve(tenant.getId(), UsageMetric.WORKFLOW_EXECUTION, 1L, "pg-q1");
    meteringService.reserve(tenant.getId(), UsageMetric.WORKFLOW_EXECUTION, 1L, "pg-q2");

    assertThatThrownBy(
            () ->
                meteringService.reserve(
                    tenant.getId(), UsageMetric.WORKFLOW_EXECUTION, 1L, "pg-q3"))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
            });

    assertThat(limitRepository.findLimits(tenant.getId()))
        .singleElement()
        .satisfies(limit -> assertThat(limit.getDailyLimit()).isEqualTo(2L));
  }

  @Test
  void reportZeroFillsMetricsWithoutUsageAndExposesRemainingQuota() {
    Tenant tenant = tenant("usage-report");
    limitRepository.save(new TenantUsageLimit(tenant.getId(), UsageMetric.AI_CALL, 10L));
    meteringService.record(tenant.getId(), UsageMetric.AI_CALL, 3L, "pg-ai");

    LocalDate today = meteringService.today();
    UsageResponse response =
        queryService.report(
            tenant.getId(), tenant.getId(), "TENANT_ADMIN", today, today, Optional.empty());

    assertThat(response.metrics()).hasSize(UsageMetric.values().length);
    UsageMetricResponse ai = metric(response, UsageMetric.AI_CALL);
    assertThat(ai.total()).isEqualTo(3L);
    assertThat(ai.limit()).isEqualTo(10L);
    assertThat(ai.remaining()).isEqualTo(7L);
    assertThat(metric(response, UsageMetric.DOCUMENT_BYTES).total()).isZero();
    assertThat(metric(response, UsageMetric.DOCUMENT_BYTES).remaining())
        .isEqualTo(UsageMetric.DOCUMENT_BYTES.defaultDailyLimit());
  }

  @Test
  void deletedTenantUsageReturns404() {
    Tenant tenant = tenant("usage-deleted");
    jdbcTemplate.update("UPDATE tenants SET deleted = true WHERE id = ?", tenant.getId());
    LocalDate today = meteringService.today();

    assertThatThrownBy(
            () ->
                queryService.report(
                    tenant.getId(), tenant.getId(), "TENANT_ADMIN", today, today, Optional.empty()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex ->
                assertThat(((AwapException) ex).errorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND));
  }

  @Test
  void nightlyReconciliationRestoresATamperedCounter() {
    Tenant tenant = tenant("usage-reconcile");
    meteringService.record(tenant.getId(), UsageMetric.WORKFLOW_EXECUTION, 4L, "pg-rec");
    LocalDate today = meteringService.today();
    jdbcTemplate.update(
        "UPDATE usage_counters SET value = 999 WHERE tenant_id = ? AND day = ?",
        tenant.getId(),
        today);

    int repaired = reconciliationService.reconcile(today, today);

    assertThat(repaired).isEqualTo(1);
    assertThat(counterRepository.findUsage(tenant.getId(), today, today))
        .singleElement()
        .satisfies(counter -> assertThat(counter.getValue()).isEqualTo(4L));
  }

  private Tenant tenant(String slugPrefix) {
    return tenantRepository.save(
        new Tenant(
            slugPrefix + "-" + UUID.randomUUID().toString().substring(0, 8),
            slugPrefix,
            "FREE",
            100,
            50,
            1000));
  }

  private UsageMetricResponse metric(UsageResponse response, UsageMetric metric) {
    return response.metrics().stream()
        .filter(row -> row.metric().equals(metric.key()))
        .findFirst()
        .orElseThrow();
  }
}
