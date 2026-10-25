package com.awap.identity.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.form.domain.FormDefinition;
import com.awap.form.infrastructure.FormDefinitionRepository;
import com.awap.identity.domain.Role;
import com.awap.identity.domain.Tenant;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.TenantRepository;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.domain.Task;
import com.awap.task.infrastructure.TaskRepository;
import com.awap.workflow.domain.WorkflowDefinition;
import com.awap.workflow.infrastructure.WorkflowDefinitionRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the file-based consumer contract over real HTTP against a PostgreSQL database, so the
 * login/refresh RLS bypass functions (SECURITY DEFINER) and the Flyway-managed schema are the same
 * ones used in production. The container runs on the superuser account, which is exempt from RLS;
 * tenant isolation itself is asserted separately by {@code TenantIsolationITest}.
 */
@Provider("awap-identity")
@PactFolder("src/test/resources/pacts")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IdentityProviderContractIT {

  /** Fixed IDs shared with the consumer pact so provider-state seeding matches the JWT claims. */
  private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID WORKFLOW_DEFINITION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000020");

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

  @Autowired private TenantRepository tenantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private TaskRepository taskRepository;
  @Autowired private FormDefinitionRepository formDefinitionRepository;
  @Autowired private WorkflowDefinitionRepository workflowDefinitionRepository;
  @Autowired private WorkflowInstanceRepository workflowInstanceRepository;
  @Autowired private PasswordEncoder passwordEncoder;

  @Value("${local.server.port}")
  private int port;

  @BeforeEach
  void setUp(PactVerificationContext pactContext) {
    pactContext.setTarget(new HttpTestTarget("localhost", port));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void verifyContract(PactVerificationContext pactContext) {
    pactContext.verifyInteraction();
  }

  @State("a tenant and an admin user exist")
  void seedLoginFixture() {
    seedIdentityFixture();
  }

  @State("an authenticated tenant admin exists")
  void seedAuthenticatedFixture() {
    seedIdentityFixture();
    seedDomainFixture();
  }

  private void seedIdentityFixture() {
    if (tenantRepository.findById(TENANT_ID).isEmpty()) {
      Tenant tenant = new Tenant("acme", "Acme Corp", "FREE", 100, 50, 1000);
      ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
      tenantRepository.saveAndFlush(tenant);
    }
    if (userRepository.findById(USER_ID).isEmpty()) {
      User user =
          new User(
              TENANT_ID,
              "ada@acme.com",
              passwordEncoder.encode("s3cret"),
              "Ada Lovelace",
              Role.TENANT_ADMIN);
      ReflectionTestUtils.setField(user, "id", USER_ID);
      userRepository.saveAndFlush(user);
    }
  }

  private void seedDomainFixture() {
    if (taskRepository.findByTenantIdAndDeletedFalse(TENANT_ID).isEmpty()) {
      taskRepository.saveAndFlush(new Task(TENANT_ID, null, null, "Review contract", null, null));
    }
    if (formDefinitionRepository.findByTenantIdAndDeletedFalse(TENANT_ID).isEmpty()) {
      formDefinitionRepository.saveAndFlush(
          new FormDefinition(TENANT_ID, "Onboarding form", "{\"fields\":[]}"));
    }
    if (workflowDefinitionRepository.findByTenantIdAndDeletedFalse(TENANT_ID).isEmpty()) {
      WorkflowDefinition definition = new WorkflowDefinition(TENANT_ID, "Approval flow", null);
      ReflectionTestUtils.setField(definition, "id", WORKFLOW_DEFINITION_ID);
      workflowDefinitionRepository.saveAndFlush(definition);
    }
    if (workflowInstanceRepository
        .findByTenantIdAndDeletedFalseOrderByCreatedAtDesc(TENANT_ID)
        .isEmpty()) {
      workflowInstanceRepository.saveAndFlush(
          new WorkflowInstance(
              TENANT_ID, WORKFLOW_DEFINITION_ID, 1, WorkflowState.RUNNING, null, "{}"));
    }
  }
}
