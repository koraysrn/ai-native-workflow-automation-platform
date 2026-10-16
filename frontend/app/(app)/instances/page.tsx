"use client";

import { useEffect, useState } from "react";
import { listWorkflowInstances, type WorkflowInstanceDto } from "../../../lib/api";

export default function InstancesPage() {
  const [instances, setInstances] = useState<WorkflowInstanceDto[]>([]);

  useEffect(() => {
    listWorkflowInstances()
      .then(setInstances)
      .catch(() => {});
  }, []);

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Workflow Instances</h1>
      <ul>
        {instances.map((instance) => (
          <li key={instance.id}>
            {instance.workflowDefinitionId} — {instance.state}
          </li>
        ))}
      </ul>
    </div>
  );
}
