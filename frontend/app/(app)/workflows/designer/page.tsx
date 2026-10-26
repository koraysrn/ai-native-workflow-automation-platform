"use client";

import { useCallback, useState } from "react";
import {
  Background,
  Controls,
  Handle,
  Position,
  ReactFlow,
  addEdge,
  useEdgesState,
  useNodesState,
  type Connection,
  type Edge,
  type Node,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { createWorkflowDefinition } from "../../../../lib/api";

type WorkflowNodeData = {
  type: string;
  name?: string;
  config?: Record<string, unknown>;
};

const nodeColors: Record<string, string> = {
  START: "#16a34a",
  END: "#e11d48",
  FORM: "#2563eb",
  APPROVAL: "#f59e0b",
  TASK: "#8b5cf6",
  DECISION: "#0ea5e9",
  CONDITION: "#06b6d4",
  PARALLEL_GATEWAY: "#7c3aed",
  MERGE_GATEWAY: "#a855f7",
  DELAY: "#f97316",
  WEBHOOK: "#dc2626",
  SUB_PROCESS: "#475569",
  AI: "#10b981",
};

function WorkflowNode({ data }: { data: WorkflowNodeData }) {
  return (
    <div
      style={{
        padding: "8px 12px",
        borderRadius: 6,
        background: "#4f46e5",
        color: "#fff",
        minWidth: 100,
        textAlign: "center",
      }}
    >
      <Handle type="target" position={Position.Top} />
      {data.name ?? data.type}
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}

const nodeTypes = { workflow: WorkflowNode };

export default function DesignerPage() {
  const [name, setName] = useState("");
  const [nodes, setNodes, onNodesChange] = useNodesState<Node<WorkflowNodeData>>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [message, setMessage] = useState("");

  const selected = nodes.find((node) => node.id === selectedId) ?? null;

  const onConnect = useCallback(
    (connection: Connection) => setEdges((eds) => addEdge(connection, eds)),
    [setEdges],
  );

  function addNode(type: string) {
    const node: Node<WorkflowNodeData> = {
      id: crypto.randomUUID(),
      type: "workflow",
      position: { x: 80 + nodes.length * 40, y: 80 + nodes.length * 40 },
      data: { type, name: type },
    };
    setNodes((nds) => [...nds, node]);
    setSelectedId(node.id);
  }

  function updateSelectedConfig(config: Record<string, unknown>) {
    setNodes((nds) =>
      nds.map((node) => (node.id === selectedId ? { ...node, data: { ...node.data, config } } : node)),
    );
  }

  function updateSelectedName(nextName: string) {
    setNodes((nds) =>
      nds.map((node) => (node.id === selectedId ? { ...node, data: { ...node.data, name: nextName } } : node)),
    );
  }

  async function save() {
    setMessage("");
    try {
      const definitionJson = JSON.stringify({
        nodes: nodes.map((node) => ({
          id: node.id,
          type: node.data.type,
          name: node.data.name ?? node.data.type,
          ...(node.data.config && Object.keys(node.data.config).length > 0
            ? { config: node.data.config }
            : {}),
        })),
        transitions: edges.map((edge) => ({ from: edge.source, to: edge.target })),
      });
      await createWorkflowDefinition({ name, definitionJson });
      setMessage("Draft saved");
    } catch {
      setMessage("Save failed");
    }
  }

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Workflow Designer</h1>
      <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
        {Object.keys(nodeColors).map((type) => (
          <button
            key={type}
            type="button"
            onClick={() => addNode(type)}
            style={{
              background: nodeColors[type] ?? "#4f46e5",
              color: "#fff",
              border: "none",
              padding: "6px 10px",
              borderRadius: 6,
            }}
          >
            {type}
          </button>
        ))}
      </div>
      <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
        <input
          placeholder="Workflow name"
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
        <button type="button" onClick={save}>
          Save draft
        </button>
      </div>

      <div style={{ display: "flex", gap: 16 }}>
        <div style={{ flex: 1, height: 480, border: "1px solid #e2e8f0", borderRadius: 8 }}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={(_, node) => setSelectedId(node.id)}
            nodeTypes={nodeTypes}
            fitView
          >
            <Background />
            <Controls />
          </ReactFlow>
        </div>

        {selected && (
          <div style={{ width: 260, border: "1px solid #e2e8f0", borderRadius: 8, padding: 12 }}>
            <h2 style={{ fontSize: 16, fontWeight: 700 }}>{selected.data.type} node</h2>
            {selected.data.type === "APPROVAL" && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Approvers (one per line, e.g. role:MANAGER)
                <textarea
                  value={((selected.data.config?.approvers as string[]) ?? []).join("\n")}
                  onChange={(event) =>
                    updateSelectedConfig({
                      ...selected.data.config,
                      approvers: event.target.value
                        .split("\n")
                        .map((line) => line.trim())
                        .filter(Boolean),
                    })
                  }
                  style={{ width: "100%", minHeight: 80, display: "block" }}
                />
              </label>
            )}
            {selected.data.type === "FORM" && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Form definition ID
                <input
                  value={(selected.data.config?.formId as string) ?? ""}
                  onChange={(event) =>
                    updateSelectedConfig({ ...selected.data.config, formId: event.target.value })
                  }
                  style={{ width: "100%", display: "block" }}
                />
              </label>
            )}
            {selected.data.type === "TASK" && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Task title
                <input
                  value={selected.data.name ?? ""}
                  onChange={(event) => updateSelectedName(event.target.value)}
                  style={{ width: "100%", display: "block" }}
                />
              </label>
            )}
            {(selected.data.type === "DECISION" || selected.data.type === "CONDITION") && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Fallback guard expression
                <textarea
                  placeholder="#amount > 100"
                  value={(selected.data.config?.expression as string) ?? ""}
                  onChange={(event) =>
                    updateSelectedConfig({
                      ...selected.data.config,
                      expression: event.target.value,
                    })
                  }
                  style={{ width: "100%", minHeight: 64, display: "block" }}
                />
                <span style={{ color: "#6b7280", fontSize: 12 }}>
                  Branch conditions are evaluated in declaration order (DECISION takes the first
                  match, CONDITION takes every match).
                </span>
              </label>
            )}
            {selected.data.type === "DELAY" && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Delay seconds
                <input
                  type="number"
                  min={0}
                  value={(selected.data.config?.delaySeconds as number) ?? 0}
                  onChange={(event) =>
                    updateSelectedConfig({
                      ...selected.data.config,
                      delaySeconds: Number(event.target.value),
                    })
                  }
                  style={{ width: "100%", display: "block" }}
                />
              </label>
            )}
            {selected.data.type === "PARALLEL_GATEWAY" && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Join node id
                <input
                  value={(selected.data.config?.joinNodeId as string) ?? ""}
                  onChange={(event) =>
                    updateSelectedConfig({
                      ...selected.data.config,
                      joinNodeId: event.target.value,
                    })
                  }
                  style={{ width: "100%", display: "block" }}
                />
              </label>
            )}
            {selected.data.type === "WEBHOOK" && (
              <>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Target URL
                  <input
                    placeholder="https://hooks.example.com"
                    value={(selected.data.config?.url as string) ?? ""}
                    onChange={(event) =>
                      updateSelectedConfig({ ...selected.data.config, url: event.target.value })
                    }
                    style={{ width: "100%", display: "block" }}
                  />
                </label>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Payload JSON
                  <textarea
                    value={(selected.data.config?.payload as string) ?? "{}"}
                    onChange={(event) =>
                      updateSelectedConfig({
                        ...selected.data.config,
                        payload: event.target.value,
                      })
                    }
                    style={{ width: "100%", minHeight: 64, display: "block" }}
                  />
                </label>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Timeout seconds
                  <input
                    type="number"
                    min={1}
                    value={(selected.data.config?.timeoutSeconds as number) ?? 10}
                    onChange={(event) =>
                      updateSelectedConfig({
                        ...selected.data.config,
                        timeoutSeconds: Number(event.target.value),
                      })
                    }
                    style={{ width: "100%", display: "block" }}
                  />
                </label>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Max attempts
                  <input
                    type="number"
                    min={1}
                    max={10}
                    value={(selected.data.config?.maxAttempts as number) ?? 3}
                    onChange={(event) =>
                      updateSelectedConfig({
                        ...selected.data.config,
                        maxAttempts: Number(event.target.value),
                      })
                    }
                    style={{ width: "100%", display: "block" }}
                  />
                </label>
              </>
            )}
            {selected.data.type === "SUB_PROCESS" && (
              <>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Child workflow definition ID
                  <input
                    value={(selected.data.config?.workflowDefinitionId as string) ?? ""}
                    onChange={(event) =>
                      updateSelectedConfig({
                        ...selected.data.config,
                        workflowDefinitionId: event.target.value,
                      })
                    }
                    style={{ width: "100%", display: "block" }}
                  />
                </label>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Version (optional)
                  <input
                    type="number"
                    min={1}
                    value={(selected.data.config?.version as number) ?? ""}
                    onChange={(event) =>
                      updateSelectedConfig({
                        ...selected.data.config,
                        version: event.target.value ? Number(event.target.value) : undefined,
                      })
                    }
                    style={{ width: "100%", display: "block" }}
                  />
                </label>
              </>
            )}
            {(selected.data.type === "START" ||
              selected.data.type === "END" ||
              selected.data.type === "MERGE_GATEWAY") && (
              <p style={{ color: "#6b7280" }}>No configuration for this node type.</p>
            )}
          </div>
        )}
      </div>

      {message && <p aria-live="polite">{message}</p>}
    </div>
  );
}
