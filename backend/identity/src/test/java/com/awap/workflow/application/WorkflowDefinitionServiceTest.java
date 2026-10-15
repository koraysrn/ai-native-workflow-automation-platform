package com.awap.workflow.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.workflow.infrastructure.WorkflowDefinitionRepository;
import com.awap.workflow.infrastructure.WorkflowVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowDefinitionServiceTest {

  @Mock private WorkflowDefinitionRepository definitionRepository;
  @Mock private WorkflowVersionRepository versionRepository;
  @Mock private ObjectMapper objectMapper;
  @Mock private SpelExpressionService spelExpressionService;

  private WorkflowDefinitionService service;

  @BeforeEach
  void setUp() {
    service =
        new WorkflowDefinitionService(
            definitionRepository, versionRepository, objectMapper, spelExpressionService);
  }

  @Test
  void tenantCannotAccessAnotherTenantDefinition() {
    UUID tenantA = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    when(definitionRepository.findByIdAndTenantIdAndDeletedFalse(id, tenantA))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(tenantA, id)).isInstanceOf(AwapException.class);
  }
}
