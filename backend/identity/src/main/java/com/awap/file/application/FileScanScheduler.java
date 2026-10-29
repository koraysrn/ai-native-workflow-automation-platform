package com.awap.file.application;

import com.awap.file.domain.FileRecord;
import com.awap.file.domain.FileScanAudit;
import com.awap.file.domain.FileScanAuditEvent;
import com.awap.file.domain.FileScanStatus;
import com.awap.file.infrastructure.FileRecordRepository;
import com.awap.file.infrastructure.FileScanAuditRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodically advances quarantine files: streams the object to ClamAV with a small buffer, then
 * either promotes a clean object (server-side copy to the promoted bucket, delete the quarantine
 * source) or records INFECTED/SCAN_FAILED with audit, metrics and retries.
 */
@Service
public class FileScanScheduler {

  public static final String SCAN_DURATION_METRIC = "awap.file.scan.duration";
  public static final String INFECTED_METRIC = "awap.file.infected";
  public static final String PROMOTE_FAIL_METRIC = "awap.file.promote.fail";

  private static final Logger log = LoggerFactory.getLogger(FileScanScheduler.class);

  private final FileRecordRepository fileRecordRepository;
  private final FileScanAuditRepository auditRepository;
  private final ClamAvScanner clamAvScanner;
  private final FileObjectStore objectStore;
  private final FileStorageProperties properties;
  private final MeterRegistry meterRegistry;

  public FileScanScheduler(
      FileRecordRepository fileRecordRepository,
      FileScanAuditRepository auditRepository,
      ClamAvScanner clamAvScanner,
      FileObjectStore objectStore,
      FileStorageProperties properties,
      MeterRegistry meterRegistry) {
    this.fileRecordRepository = fileRecordRepository;
    this.auditRepository = auditRepository;
    this.clamAvScanner = clamAvScanner;
    this.objectStore = objectStore;
    this.properties = properties;
    this.meterRegistry = meterRegistry;
  }

  @Scheduled(fixedDelayString = "${awap.files.scan-interval-ms:60000}")
  @Transactional
  public void scanPendingFiles() {
    List<FileRecord> candidates =
        fileRecordRepository.findByStatusIn(
            List.of(FileScanStatus.QUARANTINED, FileScanStatus.SCAN_FAILED, FileScanStatus.CLEAN));
    for (FileRecord record : candidates) {
      try {
        process(record);
      } catch (RuntimeException ex) {
        // One broken record must not stop the whole batch; the next tick retries.
        log.error("File scan processing failed for {}", record.getId(), ex);
      }
    }
  }

  /** Advances a single record; idempotent and safe to call repeatedly. */
  public void process(FileRecord record) {
    switch (record.getStatus()) {
      case QUARANTINED, SCAN_FAILED -> scan(record);
      case CLEAN -> promote(record);
      case PROMOTED, INFECTED -> {
        // terminal states: nothing to do
      }
    }
  }

  private void scan(FileRecord record) {
    if (!record.canRetryScan(properties.maxScanAttempts())) {
      log.warn(
          "File {} reached the maximum scan attempts ({})",
          record.getId(),
          properties.maxScanAttempts());
      return;
    }
    record.beginScan();
    Timer.Sample sample = Timer.start(meterRegistry);
    FileScanStatus result;
    try (InputStream content = objectStore.open(record.getBucket(), record.getObjectKey())) {
      result = clamAvScanner.scan(content);
    } catch (Exception ex) {
      sample.stop(timer("failed"));
      String reason = rootMessage(ex);
      record.markScanFailed(reason);
      audit(record, FileScanAuditEvent.SCAN_FAILED, reason);
      log.warn("Antivirus scan failed for {}: {}", record.getId(), reason);
      return;
    }
    sample.stop(timer(result.name().toLowerCase(java.util.Locale.ROOT)));

    if (result == FileScanStatus.CLEAN) {
      record.markClean();
      promote(record);
      return;
    }
    if (result == FileScanStatus.INFECTED) {
      record.markInfected();
      handleInfected(record);
      return;
    }
    record.markScanFailed("Unexpected scan result: " + result);
    audit(record, FileScanAuditEvent.SCAN_FAILED, "Unexpected scan result: " + result);
  }

  private void handleInfected(FileRecord record) {
    counter(INFECTED_METRIC).increment();
    audit(record, FileScanAuditEvent.INFECTED, "ClamAV reported malware; file was not promoted.");
    if (!properties.deleteInfectedObjects()) {
      log.warn("Retaining infected file {} in quarantine per policy", record.getId());
      return;
    }
    try {
      objectStore.delete(record.getBucket(), record.getObjectKey());
    } catch (RuntimeException ex) {
      log.warn(
          "Could not delete infected quarantine object {}: {}", record.getId(), ex.getMessage());
    }
  }

  /**
   * Idempotent promotion: copy to a deterministic key, then delete the source. Repeated runs either
   * see PROMOTED (no-op), retry the copy (overwrite same key) or recover a copy that already
   * completed when the record could not be updated.
   */
  private void promote(FileRecord record) {
    if (record.getStatus() == FileScanStatus.PROMOTED) {
      return;
    }
    if (!record.getStatus().isPromotable()) {
      return;
    }
    String targetBucket = properties.promotedBucket();
    String targetKey =
        FileService.objectKey(record.getTenantId(), record.getId(), record.getName());

    if (!objectStore.exists(record.getBucket(), record.getObjectKey())) {
      if (objectStore.exists(targetBucket, targetKey)) {
        record.markPromoted(targetBucket, targetKey);
        audit(
            record, FileScanAuditEvent.PROMOTED, "Recovered promotion after a prior partial run.");
        return;
      }
      promoteFailed(record, "Quarantine source object is missing.");
      return;
    }

    try {
      objectStore.copy(record.getBucket(), record.getObjectKey(), targetBucket, targetKey);
    } catch (RuntimeException ex) {
      promoteFailed(record, "Server-side copy failed: " + rootMessage(ex));
      return;
    }

    try {
      objectStore.delete(record.getBucket(), record.getObjectKey());
    } catch (RuntimeException ex) {
      // Copy is durable and deterministic; the next attempt safely repeats the copy then delete.
      promoteFailed(record, "Quarantine source delete failed: " + rootMessage(ex));
      return;
    }

    record.markPromoted(targetBucket, targetKey);
    audit(record, FileScanAuditEvent.PROMOTED, "Promoted to " + targetBucket + "/" + targetKey);
  }

  private void promoteFailed(FileRecord record, String detail) {
    counter(PROMOTE_FAIL_METRIC).increment();
    audit(record, FileScanAuditEvent.PROMOTE_FAILED, detail);
    log.warn("File promotion failed for {}: {}", record.getId(), detail);
  }

  private void audit(FileRecord record, FileScanAuditEvent event, String detail) {
    auditRepository.save(new FileScanAudit(record.getTenantId(), record.getId(), event, detail));
  }

  private Counter counter(String name) {
    return Counter.builder(name).register(meterRegistry);
  }

  private Timer timer(String result) {
    return Timer.builder(SCAN_DURATION_METRIC).tag("result", result).register(meterRegistry);
  }

  private String rootMessage(Exception ex) {
    String message = ex.getMessage();
    return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
  }
}
