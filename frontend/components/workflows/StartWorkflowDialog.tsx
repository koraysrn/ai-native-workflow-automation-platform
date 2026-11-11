"use client";

import { useState } from "react";
import { useStartWorkflow } from "../../lib/hooks/resources";

export default function StartWorkflowDialog({
  workflowId,
  onClose,
}: {
  workflowId: string;
  onClose: () => void;
}) {
  const [contextJson, setContextJson] = useState("{}");
  const [message, setMessage] = useState("");

  const start = useStartWorkflow();

  return (
    <div style={{ border: "1px solid #cbd5e1", padding: 16, marginBottom: 12, borderRadius: 8 }}>
      <h2 style={{ fontSize: 18, fontWeight: 700, margin: "0 0 8px" }}>Start workflow</h2>
      <textarea
        placeholder="Context JSON"
        aria-label="Workflow context JSON"
        value={contextJson}
        onChange={(event) => setContextJson(event.target.value)}
        style={{ display: "block", width: "100%", minHeight: 96, marginBottom: 12 }}
      />
      <div style={{ display: "flex", gap: 8 }}>
        <button
          onClick={() =>
            start.mutate(
              { workflowDefinitionId: workflowId, contextJson },
              {
                onSuccess: onClose,
                onError: () => setMessage("Start failed"),
              },
            )
          }
          disabled={start.isPending}
        >
          Start
        </button>
        <button onClick={onClose}>Cancel</button>
      </div>
      {message && <p aria-live="polite">{message}</p>}
    </div>
  );
}
