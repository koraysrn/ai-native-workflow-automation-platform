"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { approveTask, rejectTask, type TaskDto } from "../../lib/api";

export default function TaskDetail({
  task,
  onClose,
}: {
  task: TaskDto;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [comment, setComment] = useState("");

  const decide = useMutation({
    mutationFn: ({
      id,
      approve,
      comment,
    }: {
      id: string;
      approve: boolean;
      comment: string;
    }) => (approve ? approveTask(id, comment) : rejectTask(id, comment)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["tasks"] });
      onClose();
    },
  });

  return (
    <div style={{ border: "1px solid #cbd5e1", padding: 16, marginBottom: 12, borderRadius: 8 }}>
      <h2 style={{ fontSize: 20, fontWeight: 700, margin: "0 0 8px" }}>{task.title}</h2>
      <p>{task.description}</p>
      <p>
        Status: <strong>{task.status}</strong>
      </p>
      <textarea
        placeholder="Comment"
        value={comment}
        maxLength={2000}
        onChange={(event) => setComment(event.target.value)}
        style={{ display: "block", width: "100%", minHeight: 64, marginBottom: 12 }}
      />
      <div style={{ display: "flex", gap: 8 }}>
        <button
          onClick={() => decide.mutate({ id: task.id, approve: true, comment })}
          disabled={decide.isPending}
        >
          Approve
        </button>
        <button
          onClick={() => decide.mutate({ id: task.id, approve: false, comment })}
          disabled={decide.isPending}
        >
          Reject
        </button>
        <button onClick={onClose}>Close</button>
      </div>
    </div>
  );
}
