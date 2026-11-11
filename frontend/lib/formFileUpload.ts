import { createDocumentUpload, listDocuments, type DocumentVersionDto } from "./api";

/** Upload progress as a clamped whole percentage. */
export function uploadPercent(loaded: number, total: number): number {
  if (!Number.isFinite(total) || total <= 0) {
    return 0;
  }
  return Math.min(100, Math.max(0, Math.round((loaded / total) * 100)));
}

/** Only a promoted file may be referenced by a form submission (issue #200). */
export function isScanReady(status: string | null | undefined): boolean {
  return status === "PROMOTED";
}

/** A scan that will not change without operator action. */
export function isTerminalScan(status: string | null | undefined): boolean {
  return status === "PROMOTED" || status === "INFECTED" || status === "SCAN_FAILED";
}

/** Finds the live scan status of one file in the tenant document list. */
export function findScanStatus(
  documents: DocumentVersionDto[],
  fileId: string,
): string | null {
  return documents.find((document) => document.fileId === fileId)?.scanStatus ?? null;
}

export type XhrFactory = () => XMLHttpRequest;

/** PUTs the bytes to the presigned quarantine URL and reports progress. */
export function putFile(
  uploadUrl: string,
  file: Blob,
  onProgress: (percent: number) => void,
  xhrFactory: XhrFactory = () => new XMLHttpRequest(),
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = xhrFactory();
    xhr.open("PUT", uploadUrl);
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        onProgress(uploadPercent(event.loaded, event.total));
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
      } else {
        reject(new Error(`Upload failed with status ${xhr.status}.`));
      }
    };
    xhr.onerror = () => reject(new Error("Upload failed."));
    xhr.send(file);
  });
}

export interface UploadDependencies {
  createDocumentUpload: typeof createDocumentUpload;
  putFile: typeof putFile;
}

/**
 * Creates the quarantine upload slot, PUTs the file and returns the file id that a FILE form field
 * stores in the submission payload.
 */
export async function uploadFormFile(
  file: File,
  onProgress: (percent: number) => void,
  dependencies: UploadDependencies = { createDocumentUpload, putFile },
): Promise<string> {
  const slot = await dependencies.createDocumentUpload(
    file.name,
    file.type || "application/octet-stream",
    file.size,
  );
  await dependencies.putFile(slot.uploadUrl, file, onProgress);
  return slot.fileId;
}

/** Default poller used by the upload control: read the tenant document list and find the file. */
export async function pollFormFileStatus(fileId: string): Promise<string | null> {
  return findScanStatus(await listDocuments(), fileId);
}
