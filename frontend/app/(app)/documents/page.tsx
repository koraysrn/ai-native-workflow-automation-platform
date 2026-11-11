"use client";

import { useState } from "react";
import DocumentScanTable from "../../../components/documents/DocumentScanTable";
import { EmptyState, ErrorState, LoadingState } from "../../../components/ui/QueryState";
import type { DocumentVersionDto } from "../../../lib/api";
import {
  infectionMessage,
  isDocumentDownloadable,
  scanStatusAnnouncement,
} from "../../../lib/documentScanStatus";
import { useDocumentScanStatus } from "../../../lib/hooks/useDocumentScanStatus";
import {
  useArchiveDocument,
  useDocumentDownloadUrl,
  useDocuments,
  useUploadDocument,
} from "../../../lib/hooks/resources";

export default function DocumentsPage() {
  const [message, setMessage] = useState("");
  const [dismissedInfection, setDismissedInfection] = useState<string | null>(null);
  const { connected, transport, lastEvent } = useDocumentScanStatus();
  const documentsQuery = useDocuments();
  const documents = documentsQuery.data ?? [];

  const announcement = lastEvent ? scanStatusAnnouncement(lastEvent) : "";
  const infectedNotice =
    lastEvent?.status === "INFECTED" && dismissedInfection !== lastEvent.occurredAt
      ? infectionMessage(lastEvent)
      : null;

  const upload = useUploadDocument();
  const archive = useArchiveDocument();
  const downloadUrl = useDocumentDownloadUrl();

  async function download(document: DocumentVersionDto) {
    if (!isDocumentDownloadable(document)) {
      return;
    }
    const { downloadUrl: url } = await downloadUrl.mutateAsync(document.fileId);
    window.location.assign(url);
  }

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Documents</h1>
      <p style={{ color: "#6b7280" }}>
        Uploads are limited to 500 MB and PDF, DOCX, XLSX, PPTX, PNG, JPG and TXT. A file is only
        downloadable after the antivirus scan clears and promotes it.
      </p>
      <p
        data-testid="document-stream-state"
        style={{ color: "#6b7280", fontSize: 12, marginTop: 4 }}
      >
        {transport === "polling"
          ? "Live updates are unavailable; refreshing status by polling."
          : connected
            ? "Live scan status updates are connected."
            : "Connecting to live scan status updates…"}
      </p>
      <input
        type="file"
        aria-label="Upload a document"
        disabled={upload.isPending}
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (!file) {
            return;
          }
          upload.mutate(file, {
            onSuccess: () =>
              setMessage(
                "Uploaded. Scan status updates live as soon as the antivirus run completes.",
              ),
            onError: (error) =>
              setMessage(error instanceof Error ? error.message : "Upload failed"),
          });
          event.target.value = "";
        }}
      />
      {message && <p aria-live="polite">{message}</p>}
      <p aria-live="polite" aria-atomic="true" role="status" data-testid="document-scan-announcer">
        {announcement}
      </p>
      {infectedNotice && (
        <div
          role="alert"
          data-testid="document-infected-alert"
          style={{
            marginTop: 8,
            padding: 8,
            border: "1px solid #fca5a5",
            background: "#fef2f2",
            color: "#991b1b",
          }}
        >
          <strong>Infected file blocked.</strong> {infectedNotice}{" "}
          <button
            type="button"
            onClick={() => setDismissedInfection(lastEvent?.occurredAt ?? null)}
          >
            Dismiss
          </button>
        </div>
      )}
      {documentsQuery.isLoading && <LoadingState label="Loading documents…" />}
      {documentsQuery.isError && (
        <ErrorState error={documentsQuery.error} message="Failed to load documents." />
      )}
      {documentsQuery.isSuccess && documents.length === 0 && (
        <EmptyState message="No documents uploaded yet." />
      )}
      {documents.length > 0 && (
        <DocumentScanTable
          documents={documents}
          archivePending={archive.isPending}
          onDownload={download}
          onArchive={(document) =>
            archive.mutate(document.id, {
              onSuccess: () => setMessage("Version archived."),
              onError: () => setMessage("Archive failed"),
            })
          }
        />
      )}
    </div>
  );
}
