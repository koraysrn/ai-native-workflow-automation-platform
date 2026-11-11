"use client";

import { EmptyState, ErrorState, LoadingState } from "../../../components/ui/QueryState";
import { useWorkflowInstances } from "../../../lib/hooks/resources";

const stateColors: Record<string, string> = {
  COMPLETED: "#16a34a",
  FAILED: "#e11d48",
  RUNNING: "#2563eb",
  WAITING: "#f59e0b",
  CANCELLED: "#6b7280",
};

/** Live instance monitor. Polling stays here because staleness matters more than request volume. */
export default function InstancesPage() {
  const instances = useWorkflowInstances({ refetchInterval: 5000 });

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Workflow Instances</h1>
      {instances.isLoading && <LoadingState label="Loading workflow instances…" />}
      {instances.isError && (
        <ErrorState error={instances.error} message="Failed to load workflow instances." />
      )}
      {instances.isSuccess && instances.data.length === 0 && (
        <EmptyState message="No workflow instances yet." />
      )}
      <ul>
        {(instances.data ?? []).map((instance) => (
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
