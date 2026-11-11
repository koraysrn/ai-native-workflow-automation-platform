"use client";

import { useEffect, useRef, useState, type ChangeEvent } from "react";

import { scanStatusLabel } from "../../lib/documentScanStatus";
import {
  isScanReady,
  isTerminalScan,
  pollFormFileStatus,
  uploadFormFile,
} from "../../lib/formFileUpload";

export interface FormFileFieldProps {
  fieldId: string;
  onUploaded: (fileId: string) => void;
  onCleared: () => void;
  uploadFile?: typeof uploadFormFile;
  pollStatus?: (fileId: string) => Promise<string | null>;
  pollIntervalMs?: number;
}

/**
 * FILE form field (issue #200): uploads to the quarantine bucket, shows the transfer progress and
 * the live antivirus status, and only stores the file id the server will validate on submit.
 */
export default function FormFileField({
  fieldId,
  onUploaded,
  onCleared,
  uploadFile = uploadFormFile,
  pollStatus = pollFormFileStatus,
  pollIntervalMs = 2000,
}: FormFileFieldProps) {
  const [progress, setProgress] = useState(0);
  const [fileId, setFileId] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState("");
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(
    () => () => {
      if (timer.current) {
        clearInterval(timer.current);
      }
    },
    [],
  );

  function stopPolling() {
    if (timer.current) {
      clearInterval(timer.current);
      timer.current = null;
    }
  }

  async function handleChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    setError("");
    setProgress(0);
    setUploading(true);
    try {
      const uploadedId = await uploadFile(file, setProgress);
      setFileId(uploadedId);
      setStatus("QUARANTINED");
      onUploaded(uploadedId);
      stopPolling();
      timer.current = setInterval(async () => {
        try {
          const next = await pollStatus(uploadedId);
          if (next !== null) {
            setStatus(next);
          }
          if (isTerminalScan(next)) {
            stopPolling();
          }
        } catch {
          // A transient poll failure must not clear the uploaded id; the next tick retries.
        }
      }, pollIntervalMs);
    } catch {
      setError("Upload failed. Try again.");
    } finally {
      setUploading(false);
    }
  }

  function clear() {
    stopPolling();
    setFileId(null);
    setStatus(null);
    setProgress(0);
    setError("");
    onCleared();
  }

  return (
    <div style={{ marginTop: 4 }}>
      <input
        type="file"
        aria-label={`${fieldId} file`}
        onChange={handleChange}
        disabled={uploading}
      />
      {uploading ? (
        <progress
          aria-label={`${fieldId} upload progress`}
          value={progress}
          max={100}
          style={{ display: "block", width: "100%" }}
        />
      ) : null}
      {fileId ? (
        <p role="status" style={{ margin: "4px 0", fontSize: 13 }}>
          Uploaded. Scan status: {scanStatusLabel(status)}
          {isScanReady(status) ? " — ready to submit" : ""}
        </p>
      ) : null}
      {fileId ? (
        <button type="button" onClick={clear}>
          Remove file
        </button>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
    </div>
  );
}
