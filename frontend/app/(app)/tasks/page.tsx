"use client";

import { useState } from "react";
import type { TaskDto } from "../../../lib/api";
import { useTasks } from "../../../lib/hooks/resources";
import TaskDetail from "../../../components/tasks/TaskDetail";
import { EmptyState, ErrorState, LoadingState } from "../../../components/ui/QueryState";

export default function TasksPage() {
  const tasks = useTasks();
  const [selected, setSelected] = useState<TaskDto | null>(null);

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Tasks</h1>
      {selected && <TaskDetail task={selected} onClose={() => setSelected(null)} />}
      {tasks.isLoading && <LoadingState label="Loading tasks…" />}
      {tasks.isError && <ErrorState error={tasks.error} message="Failed to load tasks." />}
      {tasks.isSuccess && tasks.data.length === 0 && <EmptyState message="No tasks yet." />}
      <ul>
        {(tasks.data ?? []).map((task) => (
          <li key={task.id} style={{ marginBottom: 8 }}>
            <button
              type="button"
              onClick={() => setSelected(task)}
              style={{ background: "none", border: "none", cursor: "pointer", padding: 0 }}
            >
              {task.title}
            </button>{" "}
            — {task.status}
          </li>
        ))}
      </ul>
    </div>
  );
}
