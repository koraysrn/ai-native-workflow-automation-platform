"use client";

import { ErrorState, LoadingState } from "../../../components/ui/QueryState";
import { useTasks, useWorkflowInstances } from "../../../lib/hooks/resources";

export default function DashboardPage() {
  const tasks = useTasks();
  const instances = useWorkflowInstances();

  if (tasks.isLoading || instances.isLoading) {
    return <LoadingState label="Loading dashboard…" />;
  }
  if (tasks.isError) {
    return <ErrorState error={tasks.error} message="Failed to load tasks." />;
  }
  if (instances.isError) {
    return <ErrorState error={instances.error} message="Failed to load workflow instances." />;
  }

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Dashboard</h1>
      <p>Pending tasks: {tasks.data?.length ?? 0}</p>
      <p>Active workflow instances: {instances.data?.length ?? 0}</p>
    </div>
  );
}
