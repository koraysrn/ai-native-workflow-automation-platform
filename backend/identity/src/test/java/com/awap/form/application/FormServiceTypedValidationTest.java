package com.awap.form.application;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.form.domain.FormDefinition;
import com.awap.form.infrastructure.FormDefinitionRepository;
import com.awap.form.infrastructure.FormSubmissionRepository;
import com.awap.form.interfaces.dto.FormMapperImpl;
import com.awap.form.interfaces.dto.FormSubmissionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Type-specific server-side validation for EMAIL, PHONE, DATE, DATETIME, BOOLEAN, SELECT,
 * MULTI_SELECT and FILE fields.
 */
@ExtendWith(MockitoExtension.class)
class FormServiceTypedValidationTest {

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
            definitionRepository,
            submissionRepository,
            new ObjectMapper(),
            outboxEventPublisher,
            new FormMapperImpl());
  }

  private void givenSchema(String schemaJson) {
    FormDefinition definition = new FormDefinition(tenantId, "Form", schemaJson);
    when(definitionRepository.findByIdAndTenantIdAndDeletedFalse(formId, tenantId))
        .thenReturn(Optional.of(definition));
  }

  private void submit(String dataJson) {
    service.submit(
        tenantId, formId, new FormSubmissionRequest(dataJson, null, null), UUID.randomUUID());
  }

  @Test
  void validEmailIsAccepted() {
    givenSchema("{\"fields\":[{\"id\":\"email\",\"type\":\"EMAIL\",\"required\":true}]}");
    assertThatNoException().isThrownBy(() -> submit("{\"email\":\"user@example.com\"}"));
  }

  @Test
  void invalidEmailIsRejected() {
    givenSchema("{\"fields\":[{\"id\":\"email\",\"type\":\"EMAIL\",\"required\":true}]}");
    assertThatThrownBy(() -> submit("{\"email\":\"not-an-email\"}"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void invalidPhoneIsRejected() {
    givenSchema("{\"fields\":[{\"id\":\"phone\",\"type\":\"PHONE\",\"required\":true}]}");
    assertThatThrownBy(() -> submit("{\"phone\":\"abc\"}")).isInstanceOf(AwapException.class);
  }

  @Test
  void validPhoneIsAccepted() {
    givenSchema("{\"fields\":[{\"id\":\"phone\",\"type\":\"PHONE\",\"required\":true}]}");
    assertThatNoException().isThrownBy(() -> submit("{\"phone\":\"+90 532 123 45 67\"}"));
  }

  @Test
  void invalidDateIsRejected() {
    givenSchema("{\"fields\":[{\"id\":\"day\",\"type\":\"DATE\",\"required\":true}]}");
    assertThatThrownBy(() -> submit("{\"day\":\"2026-13-40\"}")).isInstanceOf(AwapException.class);
  }

  @Test
  void validDateIsAccepted() {
    givenSchema("{\"fields\":[{\"id\":\"day\",\"type\":\"DATE\",\"required\":true}]}");
    assertThatNoException().isThrownBy(() -> submit("{\"day\":\"2026-10-23\"}"));
  }

  @Test
  void invalidDateTimeIsRejected() {
    givenSchema("{\"fields\":[{\"id\":\"at\",\"type\":\"DATETIME\",\"required\":true}]}");
    assertThatThrownBy(() -> submit("{\"at\":\"yesterday\"}")).isInstanceOf(AwapException.class);
  }

  @Test
  void validDateTimeIsAccepted() {
    givenSchema("{\"fields\":[{\"id\":\"at\",\"type\":\"DATETIME\",\"required\":true}]}");
    assertThatNoException().isThrownBy(() -> submit("{\"at\":\"2026-10-23T16:00:00Z\"}"));
  }

  @Test
  void nonBooleanValueIsRejected() {
    givenSchema("{\"fields\":[{\"id\":\"ok\",\"type\":\"BOOLEAN\",\"required\":true}]}");
    assertThatThrownBy(() -> submit("{\"ok\":\"yes\"}")).isInstanceOf(AwapException.class);
  }

  @Test
  void validBooleanIsAccepted() {
    givenSchema("{\"fields\":[{\"id\":\"ok\",\"type\":\"BOOLEAN\",\"required\":true}]}");
    assertThatNoException().isThrownBy(() -> submit("{\"ok\":true}"));
  }

  @Test
  void selectValueOutsideOptionsIsRejected() {
    givenSchema(
        "{\"fields\":[{\"id\":\"color\",\"type\":\"SELECT\",\"required\":true,"
            + "\"options\":[\"red\",\"blue\"]}]}");
    assertThatThrownBy(() -> submit("{\"color\":\"green\"}")).isInstanceOf(AwapException.class);
  }

  @Test
  void selectValueInsideOptionsIsAccepted() {
    givenSchema(
        "{\"fields\":[{\"id\":\"color\",\"type\":\"SELECT\",\"required\":true,"
            + "\"options\":[\"red\",\"blue\"]}]}");
    assertThatNoException().isThrownBy(() -> submit("{\"color\":\"blue\"}"));
  }

  @Test
  void multiSelectWithOutsideOptionIsRejected() {
    givenSchema(
        "{\"fields\":[{\"id\":\"tags\",\"type\":\"MULTI_SELECT\",\"required\":true,"
            + "\"options\":[\"a\",\"b\"]}]}");
    assertThatThrownBy(() -> submit("{\"tags\":[\"a\",\"c\"]}")).isInstanceOf(AwapException.class);
  }

  @Test
  void multiSelectWithOnlyAllowedOptionsIsAccepted() {
    givenSchema(
        "{\"fields\":[{\"id\":\"tags\",\"type\":\"MULTI_SELECT\",\"required\":true,"
            + "\"options\":[\"a\",\"b\"]}]}");
    assertThatNoException().isThrownBy(() -> submit("{\"tags\":[\"a\",\"b\"]}"));
  }

  @Test
  void blankFileReferenceIsRejected() {
    givenSchema("{\"fields\":[{\"id\":\"file\",\"type\":\"FILE\",\"required\":true}]}");
    assertThatThrownBy(() -> submit("{\"file\":\"\"}")).isInstanceOf(AwapException.class);
  }

  @Test
  void nonBlankFileReferenceIsAccepted() {
    givenSchema("{\"fields\":[{\"id\":\"file\",\"type\":\"FILE\",\"required\":true}]}");
    assertThatNoException()
        .isThrownBy(() -> submit("{\"file\":\"550e8400-e29b-41d4-a716-446655440000\"}"));
  }
}
