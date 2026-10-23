package com.awap.form.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.NodeCompletedEvent;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.form.domain.FormDefinition;
import com.awap.form.domain.FormFieldType;
import com.awap.form.domain.FormSubmission;
import com.awap.form.infrastructure.FormDefinitionRepository;
import com.awap.form.infrastructure.FormSubmissionRepository;
import com.awap.form.interfaces.dto.FormDefinitionRequest;
import com.awap.form.interfaces.dto.FormDefinitionResponse;
import com.awap.form.interfaces.dto.FormMapper;
import com.awap.form.interfaces.dto.FormSubmissionRequest;
import com.awap.form.interfaces.dto.FormSubmissionResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Form definition and submission use cases with server-side validation. */
@Service
public class FormService {

  private static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";
  private static final String PHONE_PATTERN = "^\\+?[0-9\\s\\-()]{3,20}$";

  private final FormDefinitionRepository definitionRepository;
  private final FormSubmissionRepository submissionRepository;
  private final ObjectMapper objectMapper;
  private final OutboxEventPublisher outboxEventPublisher;
  private final FormMapper formMapper;

  public FormService(
      FormDefinitionRepository definitionRepository,
      FormSubmissionRepository submissionRepository,
      ObjectMapper objectMapper,
      OutboxEventPublisher outboxEventPublisher,
      FormMapper formMapper) {
    this.definitionRepository = definitionRepository;
    this.submissionRepository = submissionRepository;
    this.objectMapper = objectMapper;
    this.outboxEventPublisher = outboxEventPublisher;
    this.formMapper = formMapper;
  }

  @Transactional
  public FormDefinitionResponse create(UUID tenantId, FormDefinitionRequest request) {
    validateSchema(request.schemaJson());
    FormDefinition definition = new FormDefinition(tenantId, request.name(), request.schemaJson());
    definitionRepository.save(definition);
    return formMapper.toResponse(definition);
  }

  @Transactional
  public FormDefinitionResponse update(UUID tenantId, UUID id, FormDefinitionRequest request) {
    validateSchema(request.schemaJson());
    FormDefinition definition = find(tenantId, id);
    definition.updateSchema(request.name(), request.schemaJson());
    return formMapper.toResponse(definition);
  }

  public List<FormDefinitionResponse> list(UUID tenantId) {
    return definitionRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
        .map(formMapper::toResponse)
        .toList();
  }

  public FormDefinitionResponse get(UUID tenantId, UUID id) {
    return formMapper.toResponse(find(tenantId, id));
  }

  @Transactional
  public FormSubmissionResponse submit(
      UUID tenantId, UUID id, FormSubmissionRequest request, UUID submittedBy) {
    FormDefinition definition = find(tenantId, id);
    validateData(definition.getSchemaJson(), request.dataJson());
    FormSubmission submission =
        new FormSubmission(
            tenantId, id, request.workflowInstanceId(), request.dataJson(), submittedBy);
    submissionRepository.save(submission);

    if (request.workflowInstanceId() != null && request.nodeExecutionId() != null) {
      outboxEventPublisher.publish(
          tenantId,
          "FORM",
          submission.getId(),
          "FORM_SUBMITTED",
          new NodeCompletedEvent(
              tenantId,
              request.workflowInstanceId(),
              request.nodeExecutionId(),
              request.dataJson()));
    }
    return formMapper.toResponse(submission);
  }

  public List<FormSubmissionResponse> submissions(UUID tenantId, UUID id) {
    find(tenantId, id);
    return submissionRepository.findByTenantIdAndFormIdOrderByCreatedAtDesc(tenantId, id).stream()
        .map(formMapper::toResponse)
        .toList();
  }

  private FormDefinition find(UUID tenantId, UUID id) {
    return definitionRepository
        .findByIdAndTenantIdAndDeletedFalse(id, tenantId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, "Form not found."));
  }

  private void validateSchema(String schemaJson) {
    try {
      JsonNode schema = objectMapper.readTree(schemaJson);
      JsonNode fields = schema.get("fields");
      if (fields == null || !fields.isArray() || fields.isEmpty()) {
        throw invalidSchema();
      }
      for (JsonNode field : fields) {
        String type = field.path("type").asText(null);
        if (type == null || !isSupportedType(type)) {
          throw invalidSchema();
        }
      }
    } catch (JsonProcessingException ex) {
      throw invalidSchema();
    }
  }

  private boolean isSupportedType(String type) {
    for (FormFieldType fieldType : FormFieldType.values()) {
      if (fieldType.name().equals(type)) {
        return true;
      }
    }
    return false;
  }

  private void validateData(String schemaJson, String dataJson) {
    try {
      JsonNode schema = objectMapper.readTree(schemaJson);
      JsonNode data = objectMapper.readTree(dataJson);
      JsonNode fields = schema.get("fields");
      if (fields != null && fields.isArray()) {
        for (JsonNode field : fields) {
          String fieldId = field.path("id").asText(null);
          boolean required = field.path("required").asBoolean(false);
          boolean present = fieldId != null && data.has(fieldId);
          if (required && !present) {
            throw new AwapException(
                ErrorCode.VALIDATION_FAILED,
                HttpStatus.BAD_REQUEST,
                "Required field '" + fieldId + "' is missing.");
          }
          if (!present || data.get(fieldId).isNull()) {
            continue;
          }

          JsonNode value = data.get(fieldId);
          String type = field.path("type").asText(null);

          if ("TEXT".equals(type) || "TEXTAREA".equals(type) || value.isTextual()) {
            String text = value.asText();
            int minLength = field.path("minLength").asInt(-1);
            int maxLength = field.path("maxLength").asInt(-1);
            if (minLength >= 0 && text.length() < minLength) {
              throw new AwapException(
                  ErrorCode.VALIDATION_FAILED,
                  HttpStatus.BAD_REQUEST,
                  "Field '" + fieldId + "' is shorter than " + minLength + " characters.");
            }
            if (maxLength >= 0 && text.length() > maxLength) {
              throw new AwapException(
                  ErrorCode.VALIDATION_FAILED,
                  HttpStatus.BAD_REQUEST,
                  "Field '" + fieldId + "' is longer than " + maxLength + " characters.");
            }
            String pattern = field.path("pattern").asText(null);
            if (pattern != null && !text.matches(pattern)) {
              throw new AwapException(
                  ErrorCode.VALIDATION_FAILED,
                  HttpStatus.BAD_REQUEST,
                  "Field '" + fieldId + "' does not match the required pattern.");
            }
          }

          if ("NUMBER".equals(type) && value.isNumber()) {
            double number = value.asDouble();
            if (field.has("min") && number < field.get("min").asDouble()) {
              throw new AwapException(
                  ErrorCode.VALIDATION_FAILED,
                  HttpStatus.BAD_REQUEST,
                  "Field '" + fieldId + "' is below the minimum.");
            }
            if (field.has("max") && number > field.get("max").asDouble()) {
              throw new AwapException(
                  ErrorCode.VALIDATION_FAILED,
                  HttpStatus.BAD_REQUEST,
                  "Field '" + fieldId + "' is above the maximum.");
            }
          }

          validateByType(field, value);
        }
      }
    } catch (JsonProcessingException ex) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED,
          HttpStatus.BAD_REQUEST,
          "Submission data is not valid JSON.");
    }
  }

  /** Applies built-in, type-specific format validation for the typed field kinds. */
  private void validateByType(JsonNode field, JsonNode value) {
    String type = field.path("type").asText(null);
    String fieldId = field.path("id").asText(null);
    switch (type) {
      case "EMAIL" -> {
        if (!value.isTextual() || !value.asText().matches(EMAIL_PATTERN)) {
          throw invalidField(fieldId, "must be a valid email address.");
        }
      }
      case "PHONE" -> {
        if (!value.isTextual() || !value.asText().matches(PHONE_PATTERN)) {
          throw invalidField(fieldId, "must be a valid phone number.");
        }
      }
      case "DATE" -> {
        if (!value.isTextual() || !isIsoDate(value.asText())) {
          throw invalidField(fieldId, "must be a valid ISO date (YYYY-MM-DD).");
        }
      }
      case "DATETIME" -> {
        if (!value.isTextual() || !isIsoDateTime(value.asText())) {
          throw invalidField(fieldId, "must be a valid ISO-8601 date-time.");
        }
      }
      case "BOOLEAN" -> {
        if (!value.isBoolean()) {
          throw invalidField(fieldId, "must be a boolean.");
        }
      }
      case "SELECT" -> {
        if (!value.isTextual() || !matchesOptions(field, value.asText())) {
          throw invalidField(fieldId, "must be one of the allowed options.");
        }
      }
      case "MULTI_SELECT" -> {
        if (!value.isArray() || !allMatchOptions(field, value)) {
          throw invalidField(fieldId, "must contain only allowed options.");
        }
      }
      case "FILE" -> {
        if (!value.isTextual() || value.asText().isBlank()) {
          throw invalidField(fieldId, "must reference an uploaded file id.");
        }
      }
      default -> {
        // TEXT, TEXTAREA and NUMBER are validated inline; no extra type rules apply.
      }
    }
  }

  private boolean isIsoDate(String text) {
    try {
      LocalDate.parse(text);
      return true;
    } catch (DateTimeParseException ex) {
      return false;
    }
  }

  private boolean isIsoDateTime(String text) {
    try {
      OffsetDateTime.parse(text);
      return true;
    } catch (DateTimeParseException ex) {
      return false;
    }
  }

  private boolean matchesOptions(JsonNode field, String selected) {
    JsonNode options = field.get("options");
    if (options == null || !options.isArray() || options.isEmpty()) {
      return true;
    }
    for (JsonNode option : options) {
      if (selected.equals(option.asText(null))) {
        return true;
      }
    }
    return false;
  }

  private boolean allMatchOptions(JsonNode field, JsonNode values) {
    JsonNode options = field.get("options");
    if (options == null || !options.isArray() || options.isEmpty()) {
      return true;
    }
    for (JsonNode value : values) {
      if (!value.isTextual() || !matchesOptions(field, value.asText())) {
        return false;
      }
    }
    return true;
  }

  private AwapException invalidField(String fieldId, String message) {
    return new AwapException(
        ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "Field '" + fieldId + "' " + message);
  }

  private AwapException invalidSchema() {
    return new AwapException(
        ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "Form schema JSON is invalid.");
  }
}
