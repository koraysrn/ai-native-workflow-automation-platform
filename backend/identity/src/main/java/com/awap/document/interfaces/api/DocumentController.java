package com.awap.document.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.document.application.DocumentService;
import com.awap.document.domain.DocumentVersion;
import com.awap.file.application.FileService;
import com.awap.identity.infrastructure.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Document endpoints. Uploads use a presigned MinIO PUT slot; downloads are only issued after the
 * antivirus scan has cleared the file. Every lookup is tenant-scoped in the repository query.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

  private final DocumentService documentService;
  private final FileService fileService;

  public DocumentController(DocumentService documentService, FileService fileService) {
    this.documentService = documentService;
    this.fileService = fileService;
  }

  @PostMapping
  @PreAuthorize("hasAuthority('FILE_UPLOAD')")
  public ResponseEntity<CreateDocumentResponse> create(
      @Valid @RequestBody CreateDocumentRequest request) {
    UUID tenantId = currentTenantId();
    FileService.UploadSlot slot =
        fileService.createUpload(tenantId, request.name(), request.contentType(), request.size());
    DocumentVersion version = documentService.registerPending(tenantId, slot.fileId());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new CreateDocumentResponse(slot.fileId(), slot.uploadUrl(), toResponse(version)));
  }

  @GetMapping
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<List<DocumentVersionResponse>> list() {
    return ResponseEntity.ok(
        documentService.list(currentTenantId()).stream().map(this::toResponse).toList());
  }

  @GetMapping("/{fileId}/versions")
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<List<DocumentVersionResponse>> versions(@PathVariable UUID fileId) {
    return ResponseEntity.ok(
        documentService.listVersions(currentTenantId(), fileId).stream()
            .map(this::toResponse)
            .toList());
  }

  @GetMapping("/{fileId}/download")
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<Map<String, String>> download(@PathVariable UUID fileId) {
    return ResponseEntity.ok(
        Map.of("downloadUrl", fileService.downloadUrl(currentTenantId(), fileId)));
  }

  @PostMapping("/versions/{versionId}/archive")
  @PreAuthorize("hasAuthority('FILE_UPLOAD')")
  public ResponseEntity<DocumentVersionResponse> archive(@PathVariable UUID versionId) {
    return ResponseEntity.ok(toResponse(documentService.archive(currentTenantId(), versionId)));
  }

  @DeleteMapping("/versions/{versionId}")
  @PreAuthorize("hasAuthority('FILE_UPLOAD')")
  public ResponseEntity<DocumentVersionResponse> delete(@PathVariable UUID versionId) {
    return ResponseEntity.ok(toResponse(documentService.softDelete(currentTenantId(), versionId)));
  }

  private DocumentVersionResponse toResponse(DocumentVersion version) {
    return new DocumentVersionResponse(
        version.getId(),
        version.getFileId(),
        version.getVersion(),
        version.getSize(),
        version.getFormat(),
        version.getStatus().name(),
        version.getCreatedAt());
  }

  private UUID currentTenantId() {
    return TenantContext.current()
        .map(TenantContext.Context::tenantId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.UNAUTHORIZED,
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required."));
  }

  /** Request to create a presigned document upload slot. */
  public record CreateDocumentRequest(
      @NotBlank String name, String contentType, @PositiveOrZero long size) {}

  /** Upload slot + registered pending version. */
  public record CreateDocumentResponse(
      UUID fileId, String uploadUrl, DocumentVersionResponse version) {}

  /** Public representation of a document version. */
  public record DocumentVersionResponse(
      UUID id,
      UUID fileId,
      int version,
      long size,
      String format,
      String status,
      Instant createdAt) {}
}
