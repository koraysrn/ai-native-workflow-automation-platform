"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchAnalyticsSummary } from "../../../lib/api";

function formatDuration(ms: number): string {
  if (ms < 1000) {
    return `${ms} ms`;
  }
  return `${(ms / 1000).toFixed(1)} s`;
}

export default function AnalyticsPage() {
  const {
    data: aggregates = [],
    isLoading,
    isError,
  } = useQuery({
    queryKey: ["analytics-summary"],
    queryFn: () => fetchAnalyticsSummary(),
  });

  if (isLoading) {
    return <p>Loading analytics…</p>;
  }
  if (isError) {
    return <p>Failed to load analytics.</p>;
  }

  const workflowCompleted = aggregates.find((item) => item.eventType === "WORKFLOW_COMPLETED");
  const workflowFailed = aggregates.find((item) => item.eventType === "WORKFLOW_FAILED");
  const taskCompleted = aggregates.find((item) => item.eventType === "TASK_COMPLETED");

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Analytics</h1>
      <div style={{ display: "flex", gap: 16, marginBottom: 16, flexWrap: "wrap" }}>
        <MetricCard
          testId="analytics-completed-workflows"
          title="Completed workflows"
          value={String(workflowCompleted?.count ?? 0)}
          detail={`avg ${formatDuration(workflowCompleted?.averageDurationMs ?? 0)}`}
        />
        <MetricCard
          testId="analytics-failed-workflows"
          title="Failed workflows"
          value={String(workflowFailed?.count ?? 0)}
          detail="needs attention"
        />
        <MetricCard
          testId="analytics-completed-tasks"
          title="Completed tasks"
          value={String(taskCompleted?.count ?? 0)}
          detail={`avg ${formatDuration(taskCompleted?.averageDurationMs ?? 0)}`}
        />
      </div>

      <table style={{ width: "100%", borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ textAlign: "left", borderBottom: "1px solid #cbd5e1" }}>
            <th style={{ padding: 8 }}>Day</th>
            <th style={{ padding: 8 }}>Event</th>
            <th style={{ padding: 8 }}>Count</th>
            <th style={{ padding: 8 }}>Total duration</th>
            <th style={{ padding: 8 }}>Average duration</th>
          </tr>
        </thead>
        <tbody>
          {aggregates.map((item) => (
            <tr
              key={`${item.day}-${item.eventType}`}
              data-testid={`analytics-row-${item.eventType}`}
              style={{ borderBottom: "1px solid #e2e8f0" }}
            >
              <td style={{ padding: 8 }}>{item.day}</td>
              <td style={{ padding: 8 }}>{item.eventType}</td>
              <td style={{ padding: 8 }}>{item.count}</td>
              <td style={{ padding: 8 }}>{formatDuration(item.totalDurationMs)}</td>
              <td style={{ padding: 8 }}>{formatDuration(item.averageDurationMs)}</td>
            </tr>
          ))}
          {aggregates.length === 0 && (
            <tr>
              <td colSpan={5} style={{ padding: 8, color: "#6b7280" }}>
                No analytics recorded in the selected window.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function MetricCard({
  testId,
  title,
  value,
  detail,
}: {
  testId: string;
  title: string;
  value: string;
  detail: string;
}) {
  return (
    <div
      data-testid={testId}
      style={{
        border: "1px solid #cbd5e1",
        borderRadius: 8,
        padding: 16,
        minWidth: 180,
        background: "#f8fafc",
      }}
    >
      <div style={{ color: "#475569", fontSize: 13 }}>{title}</div>
      <div data-testid={`${testId}-value`} style={{ fontSize: 28, fontWeight: 700 }}>
        {value}
      </div>
      <div style={{ color: "#6b7280", fontSize: 12 }}>{detail}</div>
    </div>
  );
}
