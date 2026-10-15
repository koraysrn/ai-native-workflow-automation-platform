"use client";

import { useEffect, useState } from "react";
import { listTasks, type TaskDto } from "../../../lib/api";

export default function TasksPage() {
  const [tasks, setTasks] = useState<TaskDto[]>([]);

  useEffect(() => {
    listTasks()
      .then(setTasks)
      .catch(() => {});
  }, []);

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Tasks</h1>
      <ul>
        {tasks.map((task) => (
          <li key={task.id}>
            {task.title} — {task.status}
          </li>
        ))}
      </ul>
    </div>
  );
}
