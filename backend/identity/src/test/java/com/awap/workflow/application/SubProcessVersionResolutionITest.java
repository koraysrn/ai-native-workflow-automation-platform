package com.awap.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.IdentityApplication;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.awap.workflow.domain.WorkflowDefinition;
import com.awap.workflow.domain.WorkflowStatus;
import com.awap.workflow.domain.WorkflowVersion;
import com.awap.workflow.infrastructure.NodeDefinitionRepository;
import com.awap.workflow.infrastructure.WorkflowDefinitionRepository;
import com.awap.workflow.infrastructure.WorkflowVersionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL integration proof for SUB_PROCESS version pinning: published/rollback/archive fixtures
 * plus fifty concurrent publish/start resolutions that must always observe a complete graph.
 */
@SpringBootTest(classes = IdentityApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class SubProcessVersionResolutionITest {

  private static final int ITERATIONS = 50;

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

  @Autowired private WorkflowDefinitionService service;
  @Autowired private WorkflowDefinitionRepository definitionRepository;
  @Autowired private WorkflowVersionRepository versionRepository;
  @Autowired private NodeDefinitionRepository nodeDefinitionRepository;

  private record Resolved(int version, String nodeId) {}

  private WorkflowDefinition seedDefinition(UUID tenantId) {
    WorkflowDefinition definition = new WorkflowDefinition(tenantId, "Child flow", null);
    definition.publish(1);
    return definitionRepository.saveAndFlush(definition);
  }

  private WorkflowVersion seedVersion(
      UUID tenantId, WorkflowDefinition definition, int number, WorkflowStatus status) {
    WorkflowVersion version =
        versionRepository.saveAndFlush(
            new WorkflowVersion(tenantId, definition.getId(), number, "{}", status));
    nodeDefinitionRepository.saveAndFlush(
        new NodeDefinition(
            tenantId,
            version.getId(),
            "node-v" + number,
            NodeType.START,
            "Node v" + number,
            "{\"version\":" + number + "}",
            0));
    return version;
  }

  @Test
  void olderPublishedVersionRemainsSelectableAfterRepublish() {
    UUID tenantId = UUID.randomUUID();
    WorkflowDefinition definition = seedDefinition(tenantId);
    seedVersion(tenantId, definition, 1, WorkflowStatus.PUBLISHED);
    seedVersion(tenantId, definition, 2, WorkflowStatus.PUBLISHED);
    service.rollback(tenantId, definition.getId(), 2);

    WorkflowVersion selected = service.getPublishedVersion(tenantId, definition.getId(), 1);

    assertThat(selected.getVersion()).isEqualTo(1);
    assertThat(selected.getStatus()).isEqualTo(WorkflowStatus.PUBLISHED);
    assertThat(service.nodesOf(definition.getId(), 1))
        .extracting(NodeDefinition::getNodeId)
        .containsExactly("node-v1");
  }

  @Test
  void archivedVersionCannotBeSelected() {
    UUID tenantId = UUID.randomUUID();
    WorkflowDefinition definition = seedDefinition(tenantId);
    seedVersion(tenantId, definition, 1, WorkflowStatus.PUBLISHED);
    seedVersion(tenantId, definition, 2, WorkflowStatus.PUBLISHED);
    service.rollback(tenantId, definition.getId(), 2);
    service.archiveVersion(tenantId, definition.getId(), 2);

    assertThatThrownBy(() -> service.getPublishedVersion(tenantId, definition.getId(), 2))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.WORKFLOW_VERSION_ARCHIVED);
              assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
            });
  }

  @Test
  void crossTenantVersionSelectionIsNotFound() {
    UUID tenantId = UUID.randomUUID();
    WorkflowDefinition definition = seedDefinition(tenantId);
    seedVersion(tenantId, definition, 1, WorkflowStatus.PUBLISHED);

    assertThatThrownBy(() -> service.getPublishedVersion(UUID.randomUUID(), definition.getId(), 1))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
              assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
            });
  }

  @Test
  void fiftyConcurrentPublishAndStartCyclesAlwaysResolveACompleteGraph() throws Exception {
    UUID tenantId = UUID.randomUUID();
    WorkflowDefinition definition = seedDefinition(tenantId);
    for (int version = 1; version <= ITERATIONS; version++) {
      seedVersion(
          tenantId,
          definition,
          version,
          version == 1 ? WorkflowStatus.PUBLISHED : WorkflowStatus.DRAFT);
    }

    ExecutorService pool = Executors.newFixedThreadPool(ITERATIONS + 1);
    CountDownLatch ready = new CountDownLatch(ITERATIONS + 1);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Resolved>> starts = new ArrayList<>();
      for (int i = 0; i < ITERATIONS; i++) {
        starts.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await(10, TimeUnit.SECONDS);
                  WorkflowVersion current =
                      service.getCurrentPublishedVersion(tenantId, definition.getId());
                  List<NodeDefinition> graph =
                      service.nodesOf(definition.getId(), current.getVersion());
                  assertThat(graph).hasSize(1);
                  return new Resolved(current.getVersion(), graph.get(0).getNodeId());
                }));
      }
      Future<?> publisher =
          pool.submit(
              () -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                for (int version = 2; version <= ITERATIONS; version++) {
                  service.rollback(tenantId, definition.getId(), version);
                }
                return null;
              });

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      publisher.get(30, TimeUnit.SECONDS);
      for (Future<Resolved> start : starts) {
        Resolved resolved = start.get(30, TimeUnit.SECONDS);
        assertThat(resolved.version()).isBetween(1, ITERATIONS);
        assertThat(resolved.nodeId()).isEqualTo("node-v" + resolved.version());
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
