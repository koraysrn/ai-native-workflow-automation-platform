"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { listWorkflowDefinitions, type WorkflowDefinitionDto } from "../../../lib/api";
import StartWorkflowDialog from "../../../components/workflows/StartWorkflowDialog";

export default function WorkflowsPage() {
  const { data: workflows = [] } = useQuery({
    queryKey: ["workflow-definitions"],
    queryFn: listWorkflowDefinitions,
  });
  const [starting, setStarting] = useState<WorkflowDefinitionDto | null>(null);

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Workflows</h1>
      {starting && (
        <StartWorkflowDialog workflowId={starting.id} onClose={() => setStarting(null)} />
      )}
      <ul>
        {workflows.map((workflow) => (
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
