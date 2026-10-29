package com.awap.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.application.QuotaService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.awap.workflow.domain.WorkflowDefinition;
import com.awap.workflow.domain.WorkflowStatus;
import com.awap.workflow.domain.WorkflowVersion;
import com.awap.workflow.infrastructure.NodeDefinitionRepository;
import com.awap.workflow.infrastructure.TransitionDefinitionRepository;
import com.awap.workflow.infrastructure.WorkflowDefinitionRepository;
import com.awap.workflow.infrastructure.WorkflowVariableRepository;
import com.awap.workflow.infrastructure.WorkflowVersionRepository;
import com.awap.workflow.interfaces.dto.WorkflowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Version selection rules used to pin a SUB_PROCESS child definition. */
class WorkflowDefinitionVersionResolutionTest {

  private static final String GRAPH = "{\"nodes\":[{\"id\":\"start\",\"type\":\"START\"}]}";

  private WorkflowDefinitionService service;
  private WorkflowDefinitionRepository definitionRepository;
  private WorkflowVersionRepository versionRepository;
  private NodeDefinitionRepository nodeDefinitionRepository;
  private final UUID tenantId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    definitionRepository = mock(WorkflowDefinitionRepository.class);
    versionRepository = mock(WorkflowVersionRepository.class);
    nodeDefinitionRepository = mock(NodeDefinitionRepository.class);
    service =
        new WorkflowDefinitionService(
            definitionRepository,
            versionRepository,
            nodeDefinitionRepository,
            mock(TransitionDefinitionRepository.class),
            mock(WorkflowVariableRepository.class),
            new ObjectMapper(),
            new SpelExpressionService(),
            mock(QuotaService.class),
            mock(WorkflowMapper.class));
  }

  private WorkflowDefinition definition() {
    return new WorkflowDefinition(tenantId, "Child", null);
  }

  private WorkflowVersion version(
      WorkflowDefinition definition, int number, WorkflowStatus status) {
    return new WorkflowVersion(tenantId, definition.getId(), number, GRAPH, status);
  }

  private void stubDefinition(WorkflowDefinition definition) {
    when(definitionRepository.findByIdAndTenantIdAndDeletedFalse(definition.getId(), tenantId))
        .thenReturn(Optional.of(definition));
  }

  private void stubVersion(WorkflowDefinition definition, WorkflowVersion version) {
    when(versionRepository.findByWorkflowIdAndVersionAndTenantId(
            definition.getId(), version.getVersion(), tenantId))
        .thenReturn(Optional.of(version));
  }

  private void assertAwap(
      Runnable action, ErrorCode code, HttpStatus status, String messageFragment) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(code);
              assertThat(ex.status()).isEqualTo(status);
              assertThat(ex.getMessage()).contains(messageFragment);
            });
  }

  @Test
  void returnsPublishedVersionForTenant() {
    WorkflowDefinition definition = definition();
    WorkflowVersion version = version(definition, 1, WorkflowStatus.PUBLISHED);
    stubDefinition(definition);
    stubVersion(definition, version);

    WorkflowVersion resolved = service.getPublishedVersion(tenantId, definition.getId(), 1);

    assertThat(resolved).isSameAs(version);
    assertThat(resolved.getStatus()).isEqualTo(WorkflowStatus.PUBLISHED);
    assertThat(resolved.isDeleted()).isFalse();
  }

  @Test
  void explicitlySelectsOlderPublishedVersionAfterRollback() {
    WorkflowDefinition definition = definition();
    WorkflowVersion v1 = version(definition, 1, WorkflowStatus.PUBLISHED);
    WorkflowVersion v2 = version(definition, 2, WorkflowStatus.PUBLISHED);
    definition.publish(2);
    stubDefinition(definition);
    stubVersion(definition, v1);
    stubVersion(definition, v2);

    WorkflowVersion resolved = service.getPublishedVersion(tenantId, definition.getId(), 1);

    assertThat(resolved.getVersion()).isEqualTo(1);
    assertThat(resolved.getId()).isEqualTo(v1.getId());
  }

  @Test
  void rejectsUnpublishedVersionWithConflict() {
    WorkflowDefinition definition = definition();
    WorkflowVersion draft = version(definition, 1, WorkflowStatus.DRAFT);
    stubDefinition(definition);
    stubVersion(definition, draft);

    assertAwap(
        () -> service.getPublishedVersion(tenantId, definition.getId(), 1),
        ErrorCode.WORKFLOW_VERSION_NOT_PUBLISHED,
        HttpStatus.CONFLICT,
        "not published");
  }

  @Test
  void rejectsArchivedVersionWithConflict() {
    WorkflowDefinition definition = definition();
    WorkflowVersion archived = version(definition, 1, WorkflowStatus.PUBLISHED);
    archived.archive();
    stubDefinition(definition);
    stubVersion(definition, archived);

    assertAwap(
        () -> service.getPublishedVersion(tenantId, definition.getId(), 1),
        ErrorCode.WORKFLOW_VERSION_ARCHIVED,
        HttpStatus.CONFLICT,
        "archived");
  }

  @Test
  void rejectsMissingVersionWithNotFound() {
    WorkflowDefinition definition = definition();
    stubDefinition(definition);
    when(versionRepository.findByWorkflowIdAndVersionAndTenantId(definition.getId(), 9, tenantId))
        .thenReturn(Optional.empty());

    assertAwap(
        () -> service.getPublishedVersion(tenantId, definition.getId(), 9),
        ErrorCode.WORKFLOW_VERSION_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        "Workflow version 9");
  }

  @Test
  void rejectsNonPositiveVersionWithoutReadingRepositories() {
    UUID definitionId = UUID.randomUUID();

    assertAwap(
        () -> service.getPublishedVersion(tenantId, definitionId, 0),
        ErrorCode.WORKFLOW_VERSION_INVALID,
        HttpStatus.BAD_REQUEST,
        "positive integer");
    assertAwap(
        () -> service.getPublishedVersion(tenantId, definitionId, -1),
        ErrorCode.WORKFLOW_VERSION_INVALID,
        HttpStatus.BAD_REQUEST,
        "positive integer");
    verifyNoInteractions(definitionRepository, versionRepository);
  }

  @Test
  void crossTenantDefinitionIsNotFound() {
    UUID definitionId = UUID.randomUUID();
    when(definitionRepository.findByIdAndTenantIdAndDeletedFalse(definitionId, tenantId))
        .thenReturn(Optional.empty());

    assertAwap(
        () -> service.getPublishedVersion(tenantId, definitionId, 1),
        ErrorCode.RESOURCE_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        "Workflow definition not found");
    verifyNoInteractions(versionRepository);
  }

  @Test
  void currentPublishedVersionUsesDefinitionCurrentVersion() {
    WorkflowDefinition definition = definition();
    definition.publish(2);
    WorkflowVersion v2 = version(definition, 2, WorkflowStatus.PUBLISHED);
    stubDefinition(definition);
    stubVersion(definition, v2);

    WorkflowVersion resolved = service.getCurrentPublishedVersion(tenantId, definition.getId());

    assertThat(resolved.getVersion()).isEqualTo(2);
    assertThat(resolved.getWorkflowId()).isEqualTo(definition.getId());
  }

  @Test
  void currentPublishedVersionRejectsDraftDefinition() {
    WorkflowDefinition definition = definition();
    stubDefinition(definition);

    assertAwap(
        () -> service.getCurrentPublishedVersion(tenantId, definition.getId()),
        ErrorCode.WORKFLOW_VERSION_NOT_PUBLISHED,
        HttpStatus.CONFLICT,
        "no published version");
    verifyNoInteractions(versionRepository);
  }

  @Test
  void currentPublishedVersionRejectsArchivedCurrentVersion() {
    WorkflowDefinition definition = definition();
    definition.publish(1);
    WorkflowVersion current = version(definition, 1, WorkflowStatus.PUBLISHED);
    current.archive();
    stubDefinition(definition);
    stubVersion(definition, current);

    assertAwap(
        () -> service.getCurrentPublishedVersion(tenantId, definition.getId()),
        ErrorCode.WORKFLOW_VERSION_ARCHIVED,
        HttpStatus.CONFLICT,
        "archived");
  }

  @Test
  void twoChildrenSelectingSameVersionResolveIdenticalGraph() {
    WorkflowDefinition definition = definition();
    WorkflowVersion v1 = version(definition, 1, WorkflowStatus.PUBLISHED);
    NodeDefinition start =
        new NodeDefinition(
            tenantId, v1.getId(), "start", NodeType.START, "Start", "{\"stable\":true}", 0);
    stubDefinition(definition);
    stubVersion(definition, v1);
    when(versionRepository.findByWorkflowIdAndVersion(definition.getId(), 1))
        .thenReturn(Optional.of(v1));
    when(nodeDefinitionRepository.findByWorkflowVersionIdOrderByOrderIndexAsc(v1.getId()))
        .thenReturn(List.of(start));

    WorkflowVersion first = service.getPublishedVersion(tenantId, definition.getId(), 1);
    WorkflowVersion second = service.getPublishedVersion(tenantId, definition.getId(), 1);
    List<NodeDefinition> firstNodes = service.nodesOf(definition.getId(), first.getVersion());
    List<NodeDefinition> secondNodes = service.nodesOf(definition.getId(), second.getVersion());

    assertThat(first.getId()).isEqualTo(second.getId());
    assertThat(firstNodes).containsExactlyElementsOf(secondNodes);
    assertThat(firstNodes).extracting(NodeDefinition::getNodeId).containsExactly("start");
    assertThat(firstNodes.get(0).getConfigJson()).isEqualTo("{\"stable\":true}");
  }
}
