"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  archiveDocumentVersion,
  createDocumentUpload,
  getDocumentDownloadUrl,
  listDocuments,
  type DocumentVersionDto,
} from "../../../lib/api";

export default function DocumentsPage() {
  const queryClient = useQueryClient();
  const [message, setMessage] = useState("");
  const { data: documents = [], isLoading } = useQuery({
    queryKey: ["documents"],
    queryFn: listDocuments,
  });

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
      setMessage("Uploaded. Antivirus scanning runs before the file becomes downloadable.");
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
    const { downloadUrl } = await getDocumentDownloadUrl(document.fileId);
    window.location.assign(downloadUrl);
  }

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Documents</h1>
      <p style={{ color: "#6b7280" }}>
        Uploads are limited to 500 MB and PDF, DOCX, XLSX, PPTX, PNG, JPG and TXT. A file is only
        downloadable after the antivirus scan clears it.
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
      {isLoading ? (
        <p>Loading documents…</p>
      ) : (
        <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 12 }}>
          <thead>
            <tr style={{ textAlign: "left", borderBottom: "1px solid #cbd5e1" }}>
              <th style={{ padding: 8 }}>File</th>
              <th style={{ padding: 8 }}>Version</th>
              <th style={{ padding: 8 }}>Size</th>
              <th style={{ padding: 8 }}>Status</th>
              <th style={{ padding: 8 }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {documents.map((document) => (
              <tr key={document.id} style={{ borderBottom: "1px solid #e2e8f0" }}>
                <td style={{ padding: 8, fontFamily: "monospace", fontSize: 12 }}>
                  {document.fileId}
                </td>
                <td style={{ padding: 8 }}>v{document.version}</td>
                <td style={{ padding: 8 }}>{Math.round(document.size / 1024)} KB</td>
                <td style={{ padding: 8 }}>{document.status}</td>
                <td style={{ padding: 8, display: "flex", gap: 8 }}>
                  <button type="button" onClick={() => download(document)}>
                    Download
                  </button>
                  <button
                    type="button"
                    disabled={archive.isPending}
                    onClick={() => archive.mutate(document.id)}
                  >
                    Archive
                  </button>
                </td>
              </tr>
            ))}
            {documents.length === 0 && (
              <tr>
                <td colSpan={5} style={{ padding: 8, color: "#6b7280" }}>
                  No documents yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}
    </div>
  );
}
