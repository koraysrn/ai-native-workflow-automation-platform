package com.awap.file.application;

import com.awap.file.domain.FileRecord;
import com.awap.file.domain.FileScanStatus;
import com.awap.file.infrastructure.FileRecordRepository;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Periodically scans PENDING file records so malware is detected before download. */
@Service
public class FileScanScheduler {

  private final FileRecordRepository fileRecordRepository;
  private final ClamAvScanner clamAvScanner;

  public FileScanScheduler(FileRecordRepository fileRecordRepository, ClamAvScanner clamAvScanner) {
    this.fileRecordRepository = fileRecordRepository;
    this.clamAvScanner = clamAvScanner;
  }

  @Scheduled(fixedDelay = 60_000)
  @Transactional
  public void scanPendingFiles() {
    List<FileRecord> pending = fileRecordRepository.findByStatus(FileScanStatus.PENDING);
    for (FileRecord record : pending) {
      record.markScanResult(clamAvScanner.scan(record.getBucket(), record.getObjectKey()));
    }
  }
}
