"use client";

import { useEffect, useState } from "react";
import { listWorkflowDefinitions, type WorkflowDefinitionDto } from "../../../lib/api";

export default function WorkflowsPage() {
  const [workflows, setWorkflows] = useState<WorkflowDefinitionDto[]>([]);

  useEffect(() => {
    listWorkflowDefinitions()
      .then(setWorkflows)
      .catch(() => {});
  }, []);

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Workflows</h1>
      <ul>
        {workflows.map((workflow) => (
          <li key={workflow.id}>
            {workflow.name} — {workflow.status} (v{workflow.currentVersion})
          </li>
        ))}
      </ul>
    </div>
  );
}
