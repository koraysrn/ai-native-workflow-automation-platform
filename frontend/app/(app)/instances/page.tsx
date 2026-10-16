"use client";

import { useQuery } from "@tanstack/react-query";
import { listWorkflowInstances } from "../../../lib/api";

const stateColors: Record<string, string> = {
  COMPLETED: "#16a34a",
  FAILED: "#e11d48",
  RUNNING: "#2563eb",
  WAITING: "#f59e0b",
  CANCELLED: "#6b7280",
};

export default function InstancesPage() {
  const { data: instances = [] } = useQuery({
    queryKey: ["instances"],
    queryFn: listWorkflowInstances,
    refetchInterval: 5000,
  });

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Workflow Instances</h1>
      <ul>
        {instances.map((instance) => (
          <li key={instance.id}>
            {instance.workflowDefinitionId} —{" "}
            <span style={{ color: stateColors[instance.state] ?? "#111827", fontWeight: 600 }}>
              {instance.state}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
