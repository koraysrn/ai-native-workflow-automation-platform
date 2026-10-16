"use client";

import { useQuery } from "@tanstack/react-query";
import { listTasks, listWorkflowInstances } from "../../../lib/api";

export default function DashboardPage() {
  const { data: tasks = [] } = useQuery({
    queryKey: ["tasks"],
    queryFn: listTasks,
  });
  const { data: instances = [] } = useQuery({
    queryKey: ["instances"],
    queryFn: listWorkflowInstances,
  });

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Dashboard</h1>
      <p>Pending tasks: {tasks.length}</p>
      <p>Active workflow instances: {instances.length}</p>
    </div>
  );
}
