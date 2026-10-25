package com.awap.document.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes the {@code DocumentEvent} pipeline and drives versioned document storage. */
@Component
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class DocumentEventListener {

  private final ObjectMapper objectMapper;
  private final DocumentService documentService;

  public DocumentEventListener(ObjectMapper objectMapper, DocumentService documentService) {
    this.objectMapper = objectMapper;
    this.documentService = documentService;
  }

  @KafkaListener(topics = "document.events")
  public void onMessage(String payload) {
    try {
      JsonNode event = objectMapper.readTree(payload);
      UUID tenantId = UUID.fromString(event.path("tenantId").asText());
      UUID fileId = UUID.fromString(event.path("fileId").asText());
      long size = event.path("size").asLong();
      String format = event.path("format").isNull() ? null : event.path("format").asText();
      String objectKey = fileId + "/" + UUID.randomUUID();

      documentService.createVersion(tenantId, fileId, size, format, objectKey);
    } catch (Exception ex) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_REQUEST,
          "Invalid DocumentEvent payload: " + ex.getMessage());
    }
  }
}
