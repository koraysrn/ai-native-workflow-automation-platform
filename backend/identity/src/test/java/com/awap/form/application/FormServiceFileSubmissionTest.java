package com.awap.form.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.file.application.FileService;
import com.awap.file.application.FileService.SubmissionFile;
import com.awap.file.domain.FileScanStatus;
import com.awap.form.domain.FormDefinition;
import com.awap.form.infrastructure.FormDefinitionRepository;
import com.awap.form.infrastructure.FormSubmissionRepository;
import com.awap.form.interfaces.dto.FormMapperImpl;
import com.awap.form.interfaces.dto.FormSubmissionRequest;
import com.awap.form.interfaces.dto.FormSubmissionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** Issue #200: FILE fields must reference a scanned, tenant-owned document. */
@ExtendWith(MockitoExtension.class)
class FormServiceFileSubmissionTest {

  private static final String FILE_SCHEMA =
      "{\"fields\":[{\"id\":\"contract\",\"type\":\"FILE\",\"required\":true},"
          + "{\"id\":\"note\",\"type\":\"TEXT\"}]}";

  @Mock private FormDefinitionRepository definitionRepository;
  @Mock private FormSubmissionRepository submissionRepository;
  @Mock private OutboxEventPublisher outboxEventPublisher;
  @Mock private FileService fileService;

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
            new FormMapperImpl(),
            fileService);
    lenient()
        .when(definitionRepository.findByIdAndTenantIdAndDeletedFalse(formId, tenantId))
        .thenReturn(Optional.of(new FormDefinition(tenantId, "Form", FILE_SCHEMA)));
  }

  private FormSubmissionResponse submit(String dataJson) {
    return service.submit(
        tenantId, formId, new FormSubmissionRequest(dataJson, null, null), UUID.randomUUID());
  }

  private SubmissionFile accepted(UUID fileId) {
    return new SubmissionFile(
        fileId, "contract.pdf", 2048, "application/pdf", FileScanStatus.PROMOTED);
  }

  @Test
  void aPromotedFileIsReturnedInTheSubmissionResponse() {
    UUID fileId = UUID.randomUUID();
    when(fileService.submissionFile(tenantId, fileId)).thenReturn(accepted(fileId));

    FormSubmissionResponse response = submit("{\"contract\":\"" + fileId + "\"}");

    assertThat(response.files()).hasSize(1);
    assertThat(response.files().getFirst().fileId()).isEqualTo(fileId);
    assertThat(response.files().getFirst().name()).isEqualTo("contract.pdf");
    assertThat(response.files().getFirst().size()).isEqualTo(2048);
    assertThat(response.files().getFirst().contentType()).isEqualTo("application/pdf");
    assertThat(response.files().getFirst().scanStatus()).isEqualTo("PROMOTED");
  }

  @Test
  void theFileSubmissionEventIsPublishedThroughTheOutbox() {
    UUID fileId = UUID.randomUUID();
    when(fileService.submissionFile(tenantId, fileId)).thenReturn(accepted(fileId));

    submit("{\"contract\":\"" + fileId + "\"}");

    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(outboxEventPublisher)
        .publish(
            eq(tenantId),
            eq("FORM"),
            any(UUID.class),
            eq("FORM_FILES_SUBMITTED"),
            payload.capture());
    assertThat(payload.getValue()).isInstanceOf(FormFilesSubmittedEvent.class);
    FormFilesSubmittedEvent event = (FormFilesSubmittedEvent) payload.getValue();
    assertThat(event.formId()).isEqualTo(formId);
    assertThat(event.files()).hasSize(1);
    assertThat(event.files().getFirst().fileId()).isEqualTo(fileId);
    assertThat(event.files().getFirst().scanStatus()).isEqualTo("PROMOTED");
  }

  @Test
  void aFileIdsArrayIsAcceptedAndDeduplicated() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(fileService.submissionFile(tenantId, first)).thenReturn(accepted(first));
    when(fileService.submissionFile(tenantId, second)).thenReturn(accepted(second));

    FormSubmissionResponse response =
        submit("{\"contract\":[" + "\"" + first + "\",\"" + second + "\",\"" + first + "\"]}");

    assertThat(response.files()).hasSize(2);
    verify(fileService).submissionFile(tenantId, first);
    verify(fileService).submissionFile(tenantId, second);
  }

  @Test
  void aMissingFileReferenceIsABadRequest() {
    assertThatThrownBy(() -> submit("{\"contract\":\"\"}"))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(fileService, never()).submissionFile(any(), any());
  }

  @Test
  void aMalformedFileIdIsABadRequest() {
    assertThatThrownBy(() -> submit("{\"contract\":\"not-a-uuid\"}"))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(error.getMessage()).contains("valid file id");
            });
  }

  @Test
  void aForeignFileIsNotFound() {
    UUID fileId = UUID.randomUUID();
    when(fileService.submissionFile(tenantId, fileId))
        .thenThrow(
            new AwapException(ErrorCode.FILE_NOT_FOUND, HttpStatus.NOT_FOUND, "File not found."));

    assertThatThrownBy(() -> submit("{\"contract\":\"" + fileId + "\"}"))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(submissionRepository, never()).save(any());
  }

  @Test
  void aSubmissionWithoutFilesDoesNotPublishTheFileEvent() {
    givenSchema("{\"fields\":[{\"id\":\"note\",\"type\":\"TEXT\"}]}");

    submit("{\"note\":\"hello\"}");

    verify(outboxEventPublisher, never())
        .publish(any(), anyString(), any(), eq("FORM_FILES_SUBMITTED"), any());
  }

  @Test
  void anOptionalFileFieldMayBeOmitted() {
    givenSchema(
        "{\"fields\":[{\"id\":\"contract\",\"type\":\"FILE\"},"
            + "{\"id\":\"note\",\"type\":\"TEXT\"}]}");

    FormSubmissionResponse response = submit("{\"note\":\"hello\"}");

    assertThat(response.files()).isEmpty();
    verify(fileService, never()).submissionFile(any(), any());
  }

  @Test
  void anExplicitNullFileValueIsNotLookedUp() {
    givenSchema("{\"fields\":[{\"id\":\"contract\",\"type\":\"FILE\"}]}");

    FormSubmissionResponse response = submit("{\"contract\":null}");

    assertThat(response.files()).isEmpty();
    verify(fileService, never()).submissionFile(any(), any());
  }

  @Test
  void aSchemaWithoutFieldsProducesNoFiles() {
    givenSchema("{}");

    FormSubmissionResponse response = submit("{}");

    assertThat(response.files()).isEmpty();
  }

  @Test
  void aNonObjectSubmissionIsRejected() {
    assertThatThrownBy(() -> submit("[]"))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(error.getMessage()).contains("JSON object");
            });
  }

  @Test
  void aMalformedSubmissionIsRejected() {
    assertThatThrownBy(() -> submit("{oops"))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void aNonTextFileValueIsRejected() {
    assertThatThrownBy(() -> submit("{\"contract\":5}"))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void anArrayWithABlankEntryIsRejected() {
    assertThatThrownBy(() -> submit("{\"contract\":[\"\"]}"))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  private void givenSchema(String schemaJson) {
    when(definitionRepository.findByIdAndTenantIdAndDeletedFalse(formId, tenantId))
        .thenReturn(Optional.of(new FormDefinition(tenantId, "Form", schemaJson)));
  }
}
