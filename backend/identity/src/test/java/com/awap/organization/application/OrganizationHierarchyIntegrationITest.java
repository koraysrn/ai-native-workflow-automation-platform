package com.awap.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.IdentityApplication;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.organization.domain.OrganizationAuditEvent;
import com.awap.organization.infrastructure.CompanyRepository;
import com.awap.organization.infrastructure.OrganizationAuditEventRepository;
import com.awap.organization.interfaces.dto.CompanyRequest;
import com.awap.organization.interfaces.dto.CompanyResponse;
import com.awap.organization.interfaces.dto.CompanyTreeResponse;
import com.awap.organization.interfaces.dto.DepartmentRequest;
import com.awap.organization.interfaces.dto.DepartmentResponse;
import com.awap.organization.interfaces.dto.OrgUserRequest;
import com.awap.organization.interfaces.dto.OrgUserResponse;
import com.awap.organization.interfaces.dto.TeamRequest;
import com.awap.organization.interfaces.dto.TeamResponse;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL/RLS integration proof for the organization hierarchy (issue #195): tenant-scoped
 * reads, foreign-parent rejection, soft-delete filtering, audit retention and full tree assembly
 * are exercised against a real database.
 */
@SpringBootTest(classes = IdentityApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class OrganizationHierarchyIntegrationITest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

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

  @Autowired private OrganizationService organizationService;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private OrganizationAuditEventRepository auditEventRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate transactionTemplate;
  private UUID tenantA;
  private UUID tenantB;
  private UUID userA;

  @BeforeEach
  void seedTenants() {
    transactionTemplate = new TransactionTemplate(transactionManager);
    tenantA = UUID.randomUUID();
    tenantB = UUID.randomUUID();
    userA = UUID.randomUUID();
    insertTenant(tenantA, "a-" + tenantA);
    insertTenant(tenantB, "b-" + tenantB);
    TenantContext.set(tenantA, userA, "TENANT_ADMIN");
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void hierarchyIsTenantScopedAndForeignParentsReturn404() {
    CompanyResponse companyA =
        inTenant(
            tenantA,
            () -> organizationService.createCompany(tenantA, new CompanyRequest("Acme A")));
    CompanyResponse companyB =
        inTenant(
            tenantB,
            () -> organizationService.createCompany(tenantB, new CompanyRequest("Acme B")));

    List<CompanyResponse> visibleToA =
        inTenant(tenantA, () -> organizationService.listCompanies(tenantA));
    assertThat(visibleToA).extracting(CompanyResponse::id).containsExactly(companyA.id());
    assertThat(visibleToA).extracting(CompanyResponse::id).doesNotContain(companyB.id());

    assertThatThrownBy(
            () ->
                inTenant(
                    tenantA,
                    () ->
                        organizationService.createDepartment(
                            tenantA, new DepartmentRequest(companyB.id(), null, "Foreign"))))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex ->
                assertThat(((AwapException) ex).errorCode())
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

    assertThatThrownBy(
            () ->
                inTenant(
                    tenantA,
                    () -> organizationService.createCompany(tenantA, new CompanyRequest("Acme A"))))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> assertThat(((AwapException) ex).errorCode()).isEqualTo(ErrorCode.ORG_CONFLICT));
  }

  @Test
  void treeAssemblesTheCompleteHierarchyForTheOwningTenant() {
    CompanyTreeResponse company =
        inTenant(
            tenantA,
            () -> {
              CompanyResponse created =
                  organizationService.createCompany(tenantA, new CompanyRequest("Acme"));
              DepartmentResponse department =
                  organizationService.createDepartment(
                      tenantA, new DepartmentRequest(created.id(), null, "IT"));
              TeamResponse team =
                  organizationService.createTeam(tenantA, new TeamRequest(department.id(), "Core"));
              OrgUserResponse member =
                  organizationService.createUser(
                      tenantA, new OrgUserRequest("a@acme.com", "A", team.id(), null));
              assertThat(member.teamId()).isEqualTo(team.id());
              return organizationService.getTree(tenantA).get(0);
            });

    assertThat(company.name()).isEqualTo("Acme");
    assertThat(company.departments())
        .singleElement()
        .satisfies(
            department -> {
              assertThat(department.name()).isEqualTo("IT");
              assertThat(department.teams())
                  .singleElement()
                  .satisfies(
                      team -> {
                        assertThat(team.name()).isEqualTo("Core");
                        assertThat(team.users())
                            .extracting(OrgUserResponse::email)
                            .containsExactly("a@acme.com");
                      });
            });
  }

  @Test
  void softDeletedRowsAreExcludedButTheAuditTrailRemains() {
    UUID companyId =
        inTenant(
            tenantA,
            () -> organizationService.createCompany(tenantA, new CompanyRequest("Acme")).id());
    inTenant(
        tenantA,
        () -> {
          auditEventRepository.save(
              new OrganizationAuditEvent(
                  tenantA, "COMPANY", companyId, "CREATED", null, "Acme", userA));
          return null;
        });
    jdbcTemplate.update("UPDATE companies SET deleted = true WHERE id = ?", companyId);

    assertThat(inTenant(tenantA, () -> companyRepository.findByTenantIdAndDeletedFalse(tenantA)))
        .isEmpty();
    assertThat(
            inTenant(
                tenantA,
                () -> companyRepository.findByIdAndTenantIdAndDeletedFalse(companyId, tenantA)))
        .isEmpty();
    assertThat(
            inTenant(
                tenantA, () -> auditEventRepository.findByTenantIdOrderByCreatedAtDesc(tenantA)))
        .extracting(OrganizationAuditEvent::getEntityId)
        .contains(companyId);
  }

  private <T> T inTenant(UUID tenantId, Supplier<T> action) {
    return transactionTemplate.execute(
        status -> {
          jdbcTemplate.execute("SET LOCAL awap.tenant_id = '" + tenantId + "'");
          TenantContext.Context previous = TenantContext.current().orElse(null);
          TenantContext.set(tenantId, userA, "TENANT_ADMIN");
          try {
            return action.get();
          } finally {
            if (previous == null) {
              TenantContext.clear();
            } else {
              TenantContext.set(previous.tenantId(), previous.userId(), previous.role());
            }
          }
        });
  }

  private void insertTenant(UUID id, String slug) {
    jdbcTemplate.update(
        "INSERT INTO tenants (id, slug, name, plan, max_users, max_workflows,"
            + " max_ai_calls_per_month, active, created_at, updated_at, deleted)"
            + " VALUES (?, ?, ?, 'FREE', 10, 10, 1000, true, now(), now(), false)",
        id,
        slug,
        slug);
  }
}
