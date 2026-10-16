"use client";

import { useEffect, useState } from "react";
import { listTasks, listWorkflowInstances } from "../../../lib/api";

export default function DashboardPage() {
  const [taskCount, setTaskCount] = useState(0);
  const [instanceCount, setInstanceCount] = useState(0);

  useEffect(() => {
    listTasks()
      .then((tasks) => setTaskCount(tasks.length))
      .catch(() => {});
    listWorkflowInstances()
      .then((instances) => setInstanceCount(instances.length))
      .catch(() => {});
  }, []);

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Dashboard</h1>
      <p>Pending tasks: {taskCount}</p>
      <p>Active workflow instances: {instanceCount}</p>
    </div>
  );
}
