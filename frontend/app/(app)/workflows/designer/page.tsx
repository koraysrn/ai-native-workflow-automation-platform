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

const nodeColors: Record<string, string> = {
  START: "#16a34a",
  END: "#e11d48",
  FORM: "#2563eb",
  APPROVAL: "#f59e0b",
  TASK: "#8b5cf6",
};

function WorkflowNode({ data }: { data: { label: string } }) {
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
      {data.label}
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}

const nodeTypes = { workflow: WorkflowNode };

export default function DesignerPage() {
  const [name, setName] = useState("");
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [message, setMessage] = useState("");

  const onConnect = useCallback(
    (connection: Connection) => setEdges((eds) => addEdge(connection, eds)),
    [setEdges],
  );

  function addNode(type: string) {
    const node: Node = {
      id: crypto.randomUUID(),
      type: "workflow",
      position: { x: 80 + nodes.length * 40, y: 80 + nodes.length * 40 },
      data: { label: type },
    };
    setNodes((nds) => [...nds, node]);
  }

  async function save() {
    setMessage("");
    try {
      const definitionJson = JSON.stringify({
        nodes: nodes.map((n) => ({ id: n.id, type: n.data.label })),
        transitions: edges.map((e) => ({ from: e.source, to: e.target })),
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
              background: nodeColors[type],
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
      {message && <p aria-live="polite">{message}</p>}
      <div style={{ height: 480, border: "1px solid #e2e8f0", borderRadius: 8 }}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          nodeTypes={nodeTypes}
          fitView
        >
          <Background />
          <Controls />
        </ReactFlow>
      </div>
    </div>
  );
}
