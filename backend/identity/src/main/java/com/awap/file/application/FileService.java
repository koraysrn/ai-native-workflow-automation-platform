package com.awap.file.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.file.domain.FileRecord;
import com.awap.file.domain.FileScanStatus;
import com.awap.file.domain.FileStorageException;
import com.awap.file.infrastructure.FileRecordRepository;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * File use cases: create a quarantine upload slot (presigned PUT) and obtain a download URL for a
 * file that has been scanned and promoted.
 */
@Service
public class FileService {

  private final FileRecordRepository repository;
  private final FileObjectStore objectStore;
  private final FileStorageProperties properties;
  private final FileScanEventBus eventBus;

  public FileService(
      FileRecordRepository repository,
      FileObjectStore objectStore,
      FileStorageProperties properties,
      FileScanEventBus eventBus) {
    this.repository = repository;
    this.objectStore = objectStore;
    this.properties = properties;
    this.eventBus = eventBus;
  }

  /** Creates a presigned PUT slot that can only target the quarantine bucket. */
  @Transactional
  public UploadSlot createUpload(UUID tenantId, String name, String contentType, long size) {
    validateName(name);
    validateSize(size);
    validateFormat(name);
    ensureBuckets();

    UUID fileId = UUID.randomUUID();
    String objectKey = objectKey(tenantId, fileId, name);
    FileRecord record =
        new FileRecord(
            fileId, tenantId, name, contentType, size, properties.quarantineBucket(), objectKey);
    repository.save(record);
    eventBus.publish(FileScanStatusEvent.of(record, "Upload accepted into the quarantine bucket."));
    String uploadUrl =
        objectStore.presignedPut(properties.quarantineBucket(), objectKey, properties.presignTtl());
    return new UploadSlot(record.getId(), uploadUrl);
  }

  /** Issues a presigned GET only when the file reached PROMOTED. */
  @Transactional(readOnly = true)
  public String downloadUrl(UUID tenantId, UUID id) {
    FileRecord record = findRecord(tenantId, id);

    FileScanStatus status = record.getStatus();
    if (status == FileScanStatus.INFECTED) {
      throw new AwapException(
          ErrorCode.FILE_SCAN_REJECTED,
          HttpStatus.FORBIDDEN,
          "File was rejected by antivirus scanning.");
    }
    if (status == FileScanStatus.SCAN_FAILED) {
      throw new AwapException(
          ErrorCode.FILE_SCAN_FAILED,
          HttpStatus.SERVICE_UNAVAILABLE,
          "The antivirus scan failed; the file is not available.");
    }
    if (status != FileScanStatus.PROMOTED) {
      throw new AwapException(
          ErrorCode.FILE_SCAN_PENDING,
          HttpStatus.CONFLICT,
          "The file is still being scanned and cannot be downloaded.");
    }

    String bucket =
        record.getPromotedBucket() == null
            ? properties.promotedBucket()
            : record.getPromotedBucket();
    String key =
        record.getPromotedObjectKey() == null
            ? record.getObjectKey()
            : record.getPromotedObjectKey();
    return objectStore.presignedGet(bucket, key, properties.presignTtl());
  }

  /** Returns the current scan projection for one tenant-owned file. */
  @Transactional(readOnly = true)
  public FileScanInfo scanInfo(UUID tenantId, UUID fileId) {
    return toScanInfo(findRecord(tenantId, fileId));
  }

  /** Returns scan projections for the requested files, silently skipping foreign/missing ids. */
  @Transactional(readOnly = true)
  public List<FileScanInfo> scanInfo(UUID tenantId, Collection<UUID> fileIds) {
    if (fileIds == null || fileIds.isEmpty()) {
      return List.of();
    }
    return repository.findByIdInAndTenantId(fileIds, tenantId).stream()
        .map(this::toScanInfo)
        .toList();
  }

  /** Snapshot of all files owned by the tenant, used to seed a newly opened SSE stream. */
  @Transactional(readOnly = true)
  public List<FileScanInfo> tenantScanInfo(UUID tenantId) {
    return repository.findByTenantId(tenantId).stream().map(this::toScanInfo).toList();
  }

  private FileRecord findRecord(UUID tenantId, UUID id) {
    return repository
        .findByIdAndTenantId(id, tenantId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.FILE_NOT_FOUND, HttpStatus.NOT_FOUND, "File not found."));
  }

  private FileScanInfo toScanInfo(FileRecord record) {
    return new FileScanInfo(
        record.getId(), record.getName(), record.getStatus(), record.getLastScanError());
  }

  private void ensureBuckets() {
    try {
      objectStore.ensureBucket(
          properties.quarantineBucket(), true, properties.objectLockRetentionDays());
      objectStore.ensureBucket(
          properties.promotedBucket(), false, properties.promotedRetentionDays());
    } catch (FileStorageException ex) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.INTERNAL_SERVER_ERROR,
          "MinIO is not reachable.");
    }
  }

  private void validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "File name is required.");
    }
  }

  private void validateSize(long size) {
    if (size <= 0) {
      throw new AwapException(
          ErrorCode.FILE_EMPTY, HttpStatus.BAD_REQUEST, "File must not be empty.");
    }
    if (size > properties.maxSizeBytes()) {
      throw new AwapException(
          ErrorCode.FILE_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the 500 MB limit.");
    }
  }

  private void validateFormat(String name) {
    if (!properties.allowedExtensions().contains(extensionOf(name))) {
      throw new AwapException(
          ErrorCode.FILE_FORMAT_UNSUPPORTED,
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "File format is not supported.");
    }
  }

  private String extensionOf(String name) {
    if (name == null) {
      return "";
    }
    int dot = name.lastIndexOf('.');
    return dot >= 0 && dot < name.length() - 1
        ? name.substring(dot + 1).toLowerCase(Locale.ROOT)
        : "";
  }

  static String objectKey(UUID tenantId, UUID fileId, String name) {
    return tenantId + "/" + fileId + "/" + sanitize(name);
  }

  /** Produces a single path segment: no separators, no {@code ..} traversal sequences. */
  static String sanitize(String name) {
    if (name == null || name.isBlank()) {
      return "file";
    }
    String normalized = name.replace('\\', '/');
    String base = normalized.substring(normalized.lastIndexOf('/') + 1);
    base = base.replaceAll("[^A-Za-z0-9._-]", "_");
    base = base.replaceAll("\\.{2,}", "_");
    base = base.replaceAll("^[._]+", "_");
    return base.isBlank() ? "file" : base;
  }

  static Set<String> defaultAllowedExtensions() {
    return Set.of("pdf", "docx", "xlsx", "pptx", "png", "jpg", "jpeg", "txt");
  }

  /** Presigned upload slot returned to the client. */
  public record UploadSlot(UUID fileId, String uploadUrl) {}

  /** Public scan projection for one file; {@code downloadable} is only true after promotion. */
  public record FileScanInfo(
      UUID fileId, String name, FileScanStatus status, String lastScanError) {

    public boolean downloadable() {
      return status == FileScanStatus.PROMOTED;
    }
  }
}
