package com.awap.form.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.form.domain.FormDefinition;
import com.awap.form.infrastructure.FormDefinitionRepository;
import com.awap.form.infrastructure.FormSubmissionRepository;
import com.awap.form.interfaces.dto.FormDefinitionRequest;
import com.awap.form.interfaces.dto.FormMapper;
import com.awap.form.interfaces.dto.FormSubmissionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Negative form paths: invalid schemas, missing definitions and invalid submissions. */
class FormServiceNegativeTest {

  private FormService service;
  private FormDefinitionRepository definitionRepository;
  private final UUID tenantId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    definitionRepository = mock(FormDefinitionRepository.class);
    service =
        new FormService(
            definitionRepository,
            mock(FormSubmissionRepository.class),
            new ObjectMapper(),
            mock(OutboxEventPublisher.class),
            mock(FormMapper.class));
  }

  @Test
  void createRejectsMalformedSchemaJson() {
    assertThatThrownBy(() -> service.create(tenantId, new FormDefinitionRequest("F", "not-json")))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void createRejectsEmptyFieldsArray() {
    assertThatThrownBy(
            () -> service.create(tenantId, new FormDefinitionRequest("F", "{\"fields\":[]}")))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void createRejectsUnsupportedFieldType() {
    assertThatThrownBy(
            () ->
                service.create(
                    tenantId,
                    new FormDefinitionRequest("F", "{\"fields\":[{\"type\":\"UNKNOWN\"}]}")))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void getRejectsMissingForm() {
    UUID formId = UUID.randomUUID();
    when(definitionRepository.findByIdAndTenantIdAndDeletedFalse(formId, tenantId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(tenantId, formId)).isInstanceOf(AwapException.class);
  }

  @Test
  void submitRejectsMissingRequiredField() {
    UUID formId = UUID.randomUUID();
    FormDefinition definition =
        new FormDefinition(
            tenantId,
            "Onboarding",
            "{\"fields\":[{\"id\":\"email\",\"type\":\"TEXT\",\"required\":true}]}");
    when(definitionRepository.findByIdAndTenantIdAndDeletedFalse(formId, tenantId))
        .thenReturn(Optional.of(definition));

    assertThatThrownBy(
            () ->
                service.submit(
                    tenantId,
                    formId,
                    new FormSubmissionRequest("{}", null, null),
                    UUID.randomUUID()))
        .isInstanceOf(AwapException.class);
  }
}
