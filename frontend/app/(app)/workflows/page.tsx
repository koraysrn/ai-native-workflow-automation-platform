"use client";

import { useEffect, useState } from "react";
import {
  listWorkflowDefinitions,
  startWorkflowInstance,
  type WorkflowDefinitionDto,
} from "../../../lib/api";

export default function WorkflowsPage() {
  const [workflows, setWorkflows] = useState<WorkflowDefinitionDto[]>([]);
  const [message, setMessage] = useState("");

  useEffect(() => {
    listWorkflowDefinitions()
      .then(setWorkflows)
      .catch(() => {});
  }, []);

  async function handleStart(id: string) {
    setMessage("");
    try {
      const instance = await startWorkflowInstance(id);
      setMessage(`Started instance ${instance.id} (${instance.state})`);
    } catch {
      setMessage("Start failed");
    }
  }

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Workflows</h1>
      <ul>
        {workflows.map((workflow) => (
          <li key={workflow.id} style={{ marginBottom: 8 }}>
            {workflow.name} — {workflow.status} (v{workflow.currentVersion}){" "}
            {workflow.status === "PUBLISHED" && (
              <button onClick={() => handleStart(workflow.id)}>Start</button>
            )}
          </li>
        ))}
      </ul>
      {message && <p aria-live="polite">{message}</p>}
    </div>
  );
}
