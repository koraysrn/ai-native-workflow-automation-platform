"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import DocumentScanTable from "../../../components/documents/DocumentScanTable";
import {
  archiveDocumentVersion,
  createDocumentUpload,
  getDocumentDownloadUrl,
  listDocuments,
  type DocumentVersionDto,
} from "../../../lib/api";
import {
  infectionMessage,
  isDocumentDownloadable,
  scanStatusAnnouncement,
} from "../../../lib/documentScanStatus";
import { useDocumentScanStatus } from "../../../lib/hooks/useDocumentScanStatus";

export default function DocumentsPage() {
  const queryClient = useQueryClient();
  const [message, setMessage] = useState("");
  const [dismissedInfection, setDismissedInfection] = useState<string | null>(null);
  const { connected, transport, lastEvent } = useDocumentScanStatus();
  const { data: documents = [], isLoading } = useQuery({
    queryKey: ["documents"],
    queryFn: listDocuments,
  });

  const announcement = lastEvent ? scanStatusAnnouncement(lastEvent) : "";
  const infectedNotice =
    lastEvent?.status === "INFECTED" && dismissedInfection !== lastEvent.occurredAt
      ? infectionMessage(lastEvent)
      : null;

  const upload = useMutation({
    mutationFn: async (file: File) => {
      const slot = await createDocumentUpload(file.name, file.type, file.size);
      const response = await fetch(slot.uploadUrl, {
        method: "PUT",
        ...(file.type ? { headers: { "Content-Type": file.type } } : {}),
        body: file,
      });
      if (!response.ok) {
        throw new Error(`Upload failed with status ${response.status}`);
      }
      return slot;
    },
    onSuccess: () => {
      setMessage("Uploaded. Scan status updates live as soon as the antivirus run completes.");
      queryClient.invalidateQueries({ queryKey: ["documents"] });
    },
    onError: (error) => setMessage(error instanceof Error ? error.message : "Upload failed"),
  });

  const archive = useMutation({
    mutationFn: (versionId: string) => archiveDocumentVersion(versionId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["documents"] });
      setMessage("Version archived.");
    },
    onError: () => setMessage("Archive failed"),
  });

  async function download(document: DocumentVersionDto) {
    if (!isDocumentDownloadable(document)) {
      return;
    }
    const { downloadUrl } = await getDocumentDownloadUrl(document.fileId);
    window.location.assign(downloadUrl);
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
        disabled={upload.isPending}
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) {
            upload.mutate(file);
            event.target.value = "";
          }
        }}
      />
      {message && <p aria-live="polite">{message}</p>}
      <p
        aria-live="polite"
        aria-atomic="true"
        role="status"
        data-testid="document-scan-announcer"
      >
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
            onClick={() =>
              setDismissedInfection(lastEvent?.occurredAt ?? null)
            }
          >
            Dismiss
          </button>
        </div>
      )}
      {isLoading ? (
        <p>Loading documents…</p>
      ) : (
        <DocumentScanTable
          documents={documents}
          archivePending={archive.isPending}
          onDownload={download}
          onArchive={(document) => archive.mutate(document.id)}
        />
      )}
    </div>
  );
}
