package com.awap.file.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.file.application.FileService;
import com.awap.identity.infrastructure.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** File endpoints: create an upload slot and get a download URL (MinIO-backed). */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

  private final FileService fileService;

  public FileController(FileService fileService) {
    this.fileService = fileService;
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> createUpload(
      @Valid @RequestBody CreateFileRequest request) {
    FileService.UploadSlot slot =
        fileService.createUpload(
            currentTenantId(), request.name(), request.contentType(), request.size());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("fileId", slot.fileId(), "uploadUrl", slot.uploadUrl()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<Map<String, String>> downloadUrl(@PathVariable UUID id) {
    return ResponseEntity.ok(Map.of("downloadUrl", fileService.downloadUrl(currentTenantId(), id)));
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

  /** Request to create a presigned upload slot. */
  public record CreateFileRequest(
      @NotBlank String name, String contentType, @PositiveOrZero long size) {}
}
