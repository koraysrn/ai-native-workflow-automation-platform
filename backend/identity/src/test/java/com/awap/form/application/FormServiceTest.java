package com.awap.form.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.form.domain.FormDefinition;
import com.awap.form.infrastructure.FormDefinitionRepository;
import com.awap.form.infrastructure.FormSubmissionRepository;
import com.awap.form.interfaces.dto.FormDefinitionRequest;
import com.awap.form.interfaces.dto.FormSubmissionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FormServiceTest {

  @Mock private FormDefinitionRepository definitionRepository;
  @Mock private FormSubmissionRepository submissionRepository;
  @Mock private OutboxEventPublisher outboxEventPublisher;

  private FormService service;

  @BeforeEach
  void setUp() {
    service =
        new FormService(
            definitionRepository,
            submissionRepository,
            new ObjectMapper().findAndRegisterModules(),
            outboxEventPublisher);
  }

  @Test
  void createRejectsInvalidSchema() {
    FormDefinitionRequest request =
        new FormDefinitionRequest("Form", "{\"fields\":[{\"type\":\"UNKNOWN\"}]}");

    assertThatThrownBy(() -> service.create(UUID.randomUUID(), request))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void submitRejectsMissingRequiredField() {
    UUID tenantId = UUID.randomUUID();
    UUID formId = UUID.randomUUID();
    FormDefinition definition =
        new FormDefinition(
            tenantId, "Form", "{\"fields\":[{\"id\":\"name\",\"type\":\"TEXT\",\"required\":true}]}");
    when(definitionRepository.findByIdAndTenantIdAndDeletedFalse(formId, tenantId))
        .thenReturn(Optional.of(definition));

    FormSubmissionRequest request = new FormSubmissionRequest("{\"other\":\"x\"}", null, null);

    assertThatThrownBy(() -> service.submit(tenantId, formId, request, UUID.randomUUID()))
        .isInstanceOf(AwapException.class);
  }
}
