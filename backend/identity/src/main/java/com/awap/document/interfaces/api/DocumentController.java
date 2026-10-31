package com.awap.document.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.document.application.DocumentScanSseService;
import com.awap.document.application.DocumentService;
import com.awap.document.domain.DocumentVersion;
import com.awap.file.application.FileScanStatusEvent;
import com.awap.file.application.FileService;
import com.awap.file.application.FileService.FileScanInfo;
import com.awap.identity.infrastructure.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Document endpoints. Uploads use a presigned MinIO PUT slot; downloads are only issued after the
 * antivirus scan has cleared the file. Every lookup is tenant-scoped in the repository query.
 *
 * <p>Live scan status is exposed through Server-Sent Events. The stream is tenant-scoped, and the
 * per-file variant verifies ownership before it opens so a foreign file id can never leak another
 * tenant's filename or status.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

  private final DocumentService documentService;
  private final FileService fileService;
  private final DocumentScanSseService scanSseService;

  public DocumentController(
      DocumentService documentService,
      FileService fileService,
      DocumentScanSseService scanSseService) {
    this.documentService = documentService;
    this.fileService = fileService;
    this.scanSseService = scanSseService;
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
        .body(
            new CreateDocumentResponse(
                slot.fileId(),
                slot.uploadUrl(),
                toResponse(version, fileService.scanInfo(tenantId, slot.fileId()))));
  }

  @GetMapping
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<List<DocumentVersionResponse>> list() {
    UUID tenantId = currentTenantId();
    List<DocumentVersion> versions = documentService.list(tenantId);
    Map<UUID, FileScanInfo> scanInfo =
        scanInfoByFileId(tenantId, versions.stream().map(DocumentVersion::getFileId).toList());
    return ResponseEntity.ok(
        versions.stream()
            .map(version -> toResponse(version, scanInfo.get(version.getFileId())))
            .toList());
  }

  @GetMapping("/{fileId}/versions")
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<List<DocumentVersionResponse>> versions(@PathVariable UUID fileId) {
    UUID tenantId = currentTenantId();
    List<DocumentVersion> versions = documentService.listVersions(tenantId, fileId);
    Map<UUID, FileScanInfo> scanInfo = scanInfoByFileId(tenantId, List.of(fileId));
    return ResponseEntity.ok(
        versions.stream()
            .map(version -> toResponse(version, scanInfo.get(version.getFileId())))
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
    UUID tenantId = currentTenantId();
    DocumentVersion version = documentService.archive(tenantId, versionId);
    return ResponseEntity.ok(
        toResponse(version, fileService.scanInfo(tenantId, version.getFileId())));
  }

  @DeleteMapping("/versions/{versionId}")
  @PreAuthorize("hasAuthority('FILE_UPLOAD')")
  public ResponseEntity<DocumentVersionResponse> delete(@PathVariable UUID versionId) {
    UUID tenantId = currentTenantId();
    DocumentVersion version = documentService.softDelete(tenantId, versionId);
    scanSseService.documentDeleted(tenantId, version.getFileId());
    return ResponseEntity.ok(
        toResponse(version, fileService.scanInfo(tenantId, version.getFileId())));
  }

  /**
   * Streams every scan status change of the caller's tenant, or one file when {@code fileId} is
   * supplied. The initial snapshot closes the race between the list request and stream opening.
   */
  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<SseEmitter> events(@RequestParam(required = false) UUID fileId) {
    UUID tenantId = currentTenantId();
    UUID userId = currentUserId();
    List<FileScanStatusEvent> snapshot =
        fileId == null
            ? fileService.tenantScanInfo(tenantId).stream()
                .map(info -> toEvent(tenantId, info))
                .toList()
            : List.of(toEvent(tenantId, fileService.scanInfo(tenantId, fileId)));
    return streamResponse(scanSseService.open(tenantId, userId, fileId, snapshot));
  }

  /** Streams one document's scan status after verifying it belongs to the caller's tenant. */
  @GetMapping(value = "/{fileId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<SseEmitter> fileEvents(@PathVariable UUID fileId) {
    UUID tenantId = currentTenantId();
    UUID userId = currentUserId();
    FileScanInfo info = fileService.scanInfo(tenantId, fileId);
    return streamResponse(
        scanSseService.open(tenantId, userId, fileId, List.of(toEvent(tenantId, info))));
  }

  private ResponseEntity<SseEmitter> streamResponse(SseEmitter emitter) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
        .header("X-Accel-Buffering", "no")
        .header(HttpHeaders.CONNECTION, "keep-alive")
        .body(emitter);
  }

  private Map<UUID, FileScanInfo> scanInfoByFileId(UUID tenantId, Collection<UUID> fileIds) {
    return fileService.scanInfo(tenantId, fileIds).stream()
        .collect(Collectors.toMap(FileScanInfo::fileId, Function.identity()));
  }

  private FileScanStatusEvent toEvent(UUID tenantId, FileScanInfo info) {
    return new FileScanStatusEvent(
        tenantId, info.fileId(), info.name(), info.status(), info.lastScanError(), Instant.now());
  }

  private DocumentVersionResponse toResponse(DocumentVersion version, FileScanInfo scanInfo) {
    return new DocumentVersionResponse(
        version.getId(),
        version.getFileId(),
        version.getVersion(),
        version.getSize(),
        version.getFormat(),
        version.getStatus().name(),
        version.getCreatedAt(),
        scanInfo == null ? null : scanInfo.name(),
        scanInfo == null ? null : scanInfo.status().name(),
        scanInfo == null ? null : scanInfo.lastScanError(),
        scanInfo != null && scanInfo.downloadable());
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

  private UUID currentUserId() {
    return TenantContext.current()
        .map(TenantContext.Context::userId)
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

  /** Public representation of a document version, projected with its live scan status. */
  public record DocumentVersionResponse(
      UUID id,
      UUID fileId,
      int version,
      long size,
      String format,
      String status,
      Instant createdAt,
      String name,
      String scanStatus,
      String scanError,
      boolean downloadable) {}
}
