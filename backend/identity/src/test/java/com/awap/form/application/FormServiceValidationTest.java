package com.awap.form.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.form.domain.FormDefinition;
import com.awap.form.infrastructure.FormDefinitionRepository;
import com.awap.form.infrastructure.FormSubmissionRepository;
import com.awap.form.interfaces.dto.FormSubmissionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Server-side field validation: min/max length, regex pattern and numeric min/max. */
@ExtendWith(MockitoExtension.class)
class FormServiceValidationTest {

  @Mock private FormDefinitionRepository definitionRepository;
  @Mock private FormSubmissionRepository submissionRepository;
  @Mock private OutboxEventPublisher outboxEventPublisher;

  private FormService service;
  private final UUID tenantId = UUID.randomUUID();
  private final UUID formId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new FormService(
            definitionRepository, submissionRepository, new ObjectMapper(), outboxEventPublisher);
  }

  private void givenSchema(String schemaJson) {
    FormDefinition definition = new FormDefinition(tenantId, "Form", schemaJson);
    when(definitionRepository.findByIdAndTenantIdAndDeletedFalse(formId, tenantId))
        .thenReturn(Optional.of(definition));
  }

  @Test
  void textShorterThanMinLengthIsRejected() {
    givenSchema(
        "{\"fields\":[{\"id\":\"name\",\"type\":\"TEXT\",\"required\":true,\"minLength\":3}]}");
    assertThatThrownBy(
            () ->
                service.submit(
                    tenantId,
                    formId,
                    new FormSubmissionRequest("{\"name\":\"ab\"}", null, null),
                    UUID.randomUUID()))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void textLongerThanMaxLengthIsRejected() {
    givenSchema(
        "{\"fields\":[{\"id\":\"name\",\"type\":\"TEXT\",\"required\":true,\"maxLength\":3}]}");
    assertThatThrownBy(
            () ->
                service.submit(
                    tenantId,
                    formId,
                    new FormSubmissionRequest("{\"name\":\"abcd\"}", null, null),
                    UUID.randomUUID()))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void textNotMatchingPatternIsRejected() {
    givenSchema(
        "{\"fields\":[{\"id\":\"code\",\"type\":\"TEXT\",\"required\":true,\"pattern\":\"^[a-z]+$\"}]}");
    assertThatThrownBy(
            () ->
                service.submit(
                    tenantId,
                    formId,
                    new FormSubmissionRequest("{\"code\":\"ABC\"}", null, null),
                    UUID.randomUUID()))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void numberBelowMinimumIsRejected() {
    givenSchema(
        "{\"fields\":[{\"id\":\"amount\",\"type\":\"NUMBER\",\"required\":true,\"min\":10}]}");
    assertThatThrownBy(
            () ->
                service.submit(
                    tenantId,
                    formId,
                    new FormSubmissionRequest("{\"amount\":5}", null, null),
                    UUID.randomUUID()))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void numberAboveMaximumIsRejected() {
    givenSchema(
        "{\"fields\":[{\"id\":\"amount\",\"type\":\"NUMBER\",\"required\":true,\"max\":10}]}");
    assertThatThrownBy(
            () ->
                service.submit(
                    tenantId,
                    formId,
                    new FormSubmissionRequest("{\"amount\":15}", null, null),
                    UUID.randomUUID()))
        .isInstanceOf(AwapException.class);
  }
}
