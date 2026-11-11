"use client";

import { useState } from "react";
import type { WorkflowDefinitionDto } from "../../../lib/api";
import { useWorkflowDefinitions } from "../../../lib/hooks/resources";
import StartWorkflowDialog from "../../../components/workflows/StartWorkflowDialog";
import { EmptyState, ErrorState, LoadingState } from "../../../components/ui/QueryState";

export default function WorkflowsPage() {
  const workflows = useWorkflowDefinitions();
  const [starting, setStarting] = useState<WorkflowDefinitionDto | null>(null);

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Workflows</h1>
      {starting && (
        <StartWorkflowDialog workflowId={starting.id} onClose={() => setStarting(null)} />
      )}
      {workflows.isLoading && <LoadingState label="Loading workflows…" />}
      {workflows.isError && (
        <ErrorState error={workflows.error} message="Failed to load workflows." />
      )}
      {workflows.isSuccess && workflows.data.length === 0 && (
        <EmptyState message="No workflows yet." />
      )}
      <ul>
        {(workflows.data ?? []).map((workflow) => (
          <li key={workflow.id} style={{ marginBottom: 8 }}>
            {workflow.name} — {workflow.status} (v{workflow.currentVersion}){" "}
            {workflow.status === "PUBLISHED" && (
              <button onClick={() => setStarting(workflow)}>Start</button>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
