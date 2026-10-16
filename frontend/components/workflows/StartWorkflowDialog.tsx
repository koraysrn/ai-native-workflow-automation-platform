"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { startWorkflowInstance } from "../../lib/api";

export default function StartWorkflowDialog({
  workflowId,
  onClose,
}: {
  workflowId: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [contextJson, setContextJson] = useState("{}");
  const [message, setMessage] = useState("");

  const start = useMutation({
    mutationFn: () => startWorkflowInstance(workflowId, contextJson),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["instances"] });
      onClose();
    },
    onError: () => setMessage("Start failed"),
  });

  return (
    <div style={{ border: "1px solid #cbd5e1", padding: 16, marginBottom: 12, borderRadius: 8 }}>
      <h2 style={{ fontSize: 18, fontWeight: 700, margin: "0 0 8px" }}>Start workflow</h2>
      <textarea
        placeholder="Context JSON"
        value={contextJson}
        onChange={(event) => setContextJson(event.target.value)}
        style={{ display: "block", width: "100%", minHeight: 96, marginBottom: 12 }}
      />
      <div style={{ display: "flex", gap: 8 }}>
        <button onClick={() => start.mutate()} disabled={start.isPending}>
          Start
        </button>
        <button onClick={onClose}>Cancel</button>
      </div>
      {message && <p aria-live="polite">{message}</p>}
    </div>
  );
}
