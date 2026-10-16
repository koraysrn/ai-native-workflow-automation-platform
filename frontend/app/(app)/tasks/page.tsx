"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { listTasks, type TaskDto } from "../../../lib/api";
import TaskDetail from "../../../components/tasks/TaskDetail";

export default function TasksPage() {
  const { data: tasks = [] } = useQuery({
    queryKey: ["tasks"],
    queryFn: listTasks,
  });
  const [selected, setSelected] = useState<TaskDto | null>(null);

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Tasks</h1>
      {selected && <TaskDetail task={selected} onClose={() => setSelected(null)} />}
      <ul>
        {tasks.map((task) => (
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
