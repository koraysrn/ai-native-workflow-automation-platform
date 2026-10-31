import type { DocumentVersionDto } from "./api";

/** Antivirus lifecycle exposed by `GET /api/v1/documents/events` (issue #135). */
export type DocumentScanStatus =
  | "QUARANTINED"
  | "SCANNING"
  | "CLEAN"
  | "PROMOTED"
  | "INFECTED"
  | "SCAN_FAILED";

/** One scan-status event delivered by the tenant-scoped SSE stream. */
export interface DocumentScanStatusEventDto {
  tenantId: string;
  fileId: string;
  name: string;
  status: DocumentScanStatus;
  detail: string;
  occurredAt: string;
}

export const SCAN_STATUS_LABELS: Record<DocumentScanStatus, string> = {
  QUARANTINED: "Quarantined",
  SCANNING: "Scanning",
  CLEAN: "Clean",
  PROMOTED: "Ready to download",
  INFECTED: "Infected",
  SCAN_FAILED: "Scan failed",
};

/** Human label for a status, tolerating legacy/unknown values from older servers. */
export function scanStatusLabel(status: string | null | undefined): string {
  if (!status) {
    return "Unknown";
  }
  return SCAN_STATUS_LABELS[status as DocumentScanStatus] ?? status;
}

/** Download is gated on PROMOTED, never on the legacy document status. */
export function isDocumentDownloadable(
  document: Pick<DocumentVersionDto, "scanStatus" | "downloadable">,
): boolean {
  return document.downloadable === true && document.scanStatus === "PROMOTED";
}

/**
 * Applies one status event to the cached list in place. Unknown file ids (a document deleted during
 * the scan) are ignored, and duplicate events converge on the same row state.
 */
export function applyScanStatusEvent(
  documents: DocumentVersionDto[] | undefined,
  event: DocumentScanStatusEventDto,
): DocumentVersionDto[] | undefined {
  if (!documents) {
    return documents;
  }
  let changed = false;
  const next = documents.map((document) => {
    if (document.fileId !== event.fileId) {
      return document;
    }
    changed = true;
    return {
      ...document,
      name: event.name || document.name,
      scanStatus: event.status,
      scanError: event.detail || null,
      downloadable: event.status === "PROMOTED",
    };
  });
  return changed ? next : documents;
}

/** Removes a deleted document from the cache; returns the same array when it is absent. */
export function removeDocumentByFileId(
  documents: DocumentVersionDto[] | undefined,
  fileId: string,
): DocumentVersionDto[] | undefined {
  if (!documents) {
    return documents;
  }
  const next = documents.filter((document) => document.fileId !== fileId);
  return next.length === documents.length ? documents : next;
}

/** Screen-reader announcement for one status change. */
export function scanStatusAnnouncement(event: DocumentScanStatusEventDto): string {
  return `${event.name}: scan status changed to ${scanStatusLabel(event.status)}.`;
}

/** Clear, actionable message shown when antivirus scanning rejects a file. */
export function infectionMessage(event: DocumentScanStatusEventDto): string {
  return `${event.name} was rejected by antivirus scanning and cannot be downloaded.`;
}
