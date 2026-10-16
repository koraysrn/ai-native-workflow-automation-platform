"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  approveTask,
  listTasks,
  rejectTask,
  type TaskDto,
} from "../../../lib/api";

export default function TasksPage() {
  const queryClient = useQueryClient();
  const { data: tasks = [] } = useQuery({
    queryKey: ["tasks"],
    queryFn: listTasks,
  });

  const decide = useMutation({
    mutationFn: ({ id, approve }: { id: string; approve: boolean }) =>
      approve ? approveTask(id) : rejectTask(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["tasks"] }),
  });

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Tasks</h1>
      <ul>
        {tasks.map((task: TaskDto) => (
          <li key={task.id} style={{ marginBottom: 8 }}>
            {task.title} — {task.status}{" "}
            {(task.status === "ASSIGNED" || task.status === "IN_PROGRESS") && (
              <>
                <button
                  onClick={() => decide.mutate({ id: task.id, approve: true })}
                  disabled={decide.isPending}
                >
                  Approve
                </button>
                <button
                  onClick={() => decide.mutate({ id: task.id, approve: false })}
                  disabled={decide.isPending}
                >
                  Reject
                </button>
              </>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
