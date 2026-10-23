package com.awap.file.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.file.domain.FileRecord;
import com.awap.file.domain.FileScanStatus;
import com.awap.file.infrastructure.FileRecordRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies the antivirus scheduler marks pending records with the scanner result. */
@ExtendWith(MockitoExtension.class)
class FileScanSchedulerTest {

  @Mock private FileRecordRepository fileRecordRepository;
  @Mock private ClamAvScanner clamAvScanner;

  @Test
  void marksPendingRecordsWithScanResult() {
    FileScanScheduler scheduler = new FileScanScheduler(fileRecordRepository, clamAvScanner);
    FileRecord record =
        new FileRecord(UUID.randomUUID(), "file.pdf", "application/pdf", 10, "bucket", "key");
    when(fileRecordRepository.findByStatus(FileScanStatus.PENDING)).thenReturn(List.of(record));
    when(clamAvScanner.scan("bucket", "key")).thenReturn(FileScanStatus.CLEAN);

    scheduler.scanPendingFiles();

    verify(clamAvScanner).scan("bucket", "key");
    org.assertj.core.api.Assertions.assertThat(record.getStatus()).isEqualTo(FileScanStatus.CLEAN);
  }
}
