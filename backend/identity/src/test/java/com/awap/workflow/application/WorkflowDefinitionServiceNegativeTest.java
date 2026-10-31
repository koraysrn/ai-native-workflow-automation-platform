package com.awap.workflow.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.awap.common.api.AwapException;
import com.awap.identity.application.QuotaService;
import com.awap.workflow.infrastructure.NodeDefinitionRepository;
import com.awap.workflow.infrastructure.TransitionDefinitionRepository;
import com.awap.workflow.infrastructure.WorkflowDefinitionRepository;
import com.awap.workflow.infrastructure.WorkflowVariableRepository;
import com.awap.workflow.infrastructure.WorkflowVersionRepository;
import com.awap.workflow.interfaces.dto.WorkflowDefinitionRequest;
import com.awap.workflow.interfaces.dto.WorkflowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Negative workflow-definition paths: malformed graphs and forbidden SpEL expressions. */
class WorkflowDefinitionServiceNegativeTest {

  private WorkflowDefinitionService service;
  private final UUID tenantId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new WorkflowDefinitionService(
            mock(WorkflowDefinitionRepository.class),
            mock(WorkflowVersionRepository.class),
            mock(NodeDefinitionRepository.class),
            mock(TransitionDefinitionRepository.class),
            mock(WorkflowVariableRepository.class),
            new ObjectMapper(),
            new SpelExpressionService(),
            mock(QuotaService.class),
            mock(WorkflowMapper.class));
  }

  private void assertInvalid(String definitionJson) {
    assertThatThrownBy(
            () ->
                service.create(tenantId, new WorkflowDefinitionRequest("W", null, definitionJson)))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void rejectsMalformedJson() {
    assertInvalid("{not json");
  }

  @Test
  void rejectsMissingNodes() {
    assertInvalid("{\"transitions\":[]}");
  }

  @Test
  void rejectsEmptyNodes() {
    assertInvalid("{\"nodes\":[]}");
  }

  @Test
  void rejectsUnsupportedNodeType() {
    assertInvalid("{\"nodes\":[{\"id\":\"a\",\"type\":\"MAGIC\"}]}");
  }

  @Test
  void rejectsNodeWithoutId() {
    assertInvalid("{\"nodes\":[{\"type\":\"START\"}]}");
  }

  @Test
  void rejectsTransitionToUnknownNode() {
    assertInvalid(
        "{\"nodes\":[{\"id\":\"a\",\"type\":\"START\"}],"
            + "\"transitions\":[{\"from\":\"a\",\"to\":\"missing\"}]}");
  }

  @Test
  void rejectsForbiddenSpelInNodeCondition() {
    assertInvalid(
        "{\"nodes\":[{\"id\":\"a\",\"type\":\"TASK\","
            + "\"condition\":\"T(java.lang.Runtime).getRuntime()\"}]}");
  }

  @Test
  void rejectsForbiddenSpelInTransitionCondition() {
    assertInvalid(
        "{\"nodes\":[{\"id\":\"a\",\"type\":\"START\"},{\"id\":\"b\",\"type\":\"END\"}],"
            + "\"transitions\":[{\"from\":\"a\",\"to\":\"b\","
            + "\"condition\":\"new java.lang.ProcessBuilder()\"}]}");
  }

  @Test
  void rejectsAnOversizedTransitionCondition() {
    String oversized = "1".repeat(SpelExpressionService.MAX_EXPRESSION_LENGTH + 1);

    assertInvalid(
        "{\"nodes\":[{\"id\":\"a\",\"type\":\"START\"},{\"id\":\"b\",\"type\":\"END\"}],"
            + "\"transitions\":[{\"from\":\"a\",\"to\":\"b\",\"condition\":\""
            + oversized
            + "\"}]}");
  }

  @Test
  void acceptsABlankTransitionConditionAsUnconditional() {
    service.create(
        tenantId,
        new WorkflowDefinitionRequest(
            "W",
            null,
            "{\"nodes\":[{\"id\":\"a\",\"type\":\"START\"},{\"id\":\"b\",\"type\":\"END\"}],"
                + "\"transitions\":[{\"from\":\"a\",\"to\":\"b\",\"condition\":\"   \"}]}"));
  }
}
