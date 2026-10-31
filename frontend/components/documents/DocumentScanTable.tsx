"use client";

import type { DocumentVersionDto } from "../../lib/api";
import {
  isDocumentDownloadable,
  scanStatusLabel,
} from "../../lib/documentScanStatus";

export interface DocumentScanTableProps {
  documents: DocumentVersionDto[];
  archivePending: boolean;
  onDownload: (document: DocumentVersionDto) => void;
  onArchive: (document: DocumentVersionDto) => void;
}

/**
 * Document rows with the live antivirus scan status. Download stays disabled until the row reaches
 * PROMOTED, so a stale UI can never hand out a URL for a quarantined or infected file.
 */
export default function DocumentScanTable({
  documents,
  archivePending,
  onDownload,
  onArchive,
}: DocumentScanTableProps) {
  return (
    <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 12 }}>
      <thead>
        <tr style={{ textAlign: "left", borderBottom: "1px solid #cbd5e1" }}>
          <th style={{ padding: 8 }}>File</th>
          <th style={{ padding: 8 }}>Version</th>
          <th style={{ padding: 8 }}>Size</th>
          <th style={{ padding: 8 }}>Scan status</th>
          <th style={{ padding: 8 }}>Actions</th>
        </tr>
      </thead>
      <tbody>
        {documents.map((document) => {
          const downloadable = isDocumentDownloadable(document);
          const status = document.scanStatus;
          return (
            <tr
              key={document.id}
              data-testid={`document-row-${document.fileId}`}
              style={{ borderBottom: "1px solid #e2e8f0" }}
            >
              <td
                style={{
                  padding: 8,
                  maxWidth: 260,
                  overflowWrap: "anywhere",
                  fontFamily: "monospace",
                  fontSize: 12,
                }}
              >
                {document.name ?? document.fileId}
              </td>
              <td style={{ padding: 8 }}>v{document.version}</td>
              <td style={{ padding: 8 }}>{Math.round(document.size / 1024)} KB</td>
              <td style={{ padding: 8 }}>
                <span data-testid={`scan-status-${document.fileId}`}>
                  {scanStatusLabel(status)}
                </span>
                {status === "SCAN_FAILED" && document.scanError ? (
                  <div style={{ color: "#b91c1c", fontSize: 12 }}>{document.scanError}</div>
                ) : null}
              </td>
              <td style={{ padding: 8, display: "flex", gap: 8 }}>
                <button
                  type="button"
                  disabled={!downloadable}
                  title={
                    downloadable
                      ? "Download the promoted file"
                      : "Available only after the antivirus scan promotes the file"
                  }
                  onClick={() => onDownload(document)}
                >
                  Download
                </button>
                <button
                  type="button"
                  disabled={archivePending}
                  onClick={() => onArchive(document)}
                >
                  Archive
                </button>
              </td>
            </tr>
          );
        })}
        {documents.length === 0 && (
          <tr>
            <td colSpan={5} style={{ padding: 8, color: "#6b7280" }}>
              No documents yet.
            </td>
          </tr>
        )}
      </tbody>
    </table>
  );
}
