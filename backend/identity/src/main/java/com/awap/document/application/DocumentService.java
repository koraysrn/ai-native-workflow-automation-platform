package com.awap.document.application;

import com.awap.document.domain.DocumentStatus;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Document module skeleton (ADR-003). Faz 1 implements versioned storage and the {@code
 * DocumentEvent} pipeline on top of the existing MinIO/file layer.
 */
@Service
public class DocumentService {

  private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

  public void register(UUID tenantId, UUID fileId, DocumentStatus status) {
    log.debug("Document registered: tenant={} fileId={} status={}", tenantId, fileId, status);
  }
}
