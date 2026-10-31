"use client";

import { useCallback, useMemo, useState, type KeyboardEvent } from "react";
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
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { createWorkflowDefinition, validateWorkflowExpression } from "../../../../lib/api";
import {
  DEFAULT_WORKFLOW_VARIABLES,
  validateExpression,
} from "../../../../lib/workflowExpressions";
import {
  conditionOf,
  duplicateEdge,
  toDefinitionJson,
  withConditionIndicators,
  type WorkflowEdge,
  type WorkflowNode,
  type WorkflowNodeData,
} from "../../../../lib/workflowGraph";
import ConditionalEdge from "../../../../components/workflows/ConditionalEdge";
import DesignerLegend from "../../../../components/workflows/DesignerLegend";
import EdgeConditionEditor from "../../../../components/workflows/EdgeConditionEditor";

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

const workflowVariables = [...DEFAULT_WORKFLOW_VARIABLES];

/**
 * Darkens a node-type colour so the white toolbar label keeps at least a 4.5:1 WCAG AA contrast.
 * Axe flags several of the brighter palette entries (for example #10b981 and #f59e0b) otherwise.
 */
function darkenHex(hex: string, factor = 0.6): string {
  const value = Number.parseInt(hex.replace("#", ""), 16);
  const channels = [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff].map((channel) =>
    Math.round(channel * factor)
      .toString(16)
      .padStart(2, "0"),
  );
  return `#${channels.join("")}`;
}

function WorkflowNodeCard({ data }: { data: WorkflowNodeData }) {
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

const nodeTypes = { workflow: WorkflowNodeCard };
const edgeTypes = { conditional: ConditionalEdge };

export default function DesignerPage() {
  const [name, setName] = useState("");
  const [nodes, setNodes, onNodesChange] = useNodesState<WorkflowNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<WorkflowEdge>([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);

  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null;
  const selectedEdge = edges.find((edge) => edge.id === selectedEdgeId) ?? null;
  const displayEdges = useMemo(() => withConditionIndicators(edges), [edges]);
  const invalidEdges = edges.filter((edge) => !validateExpression(conditionOf(edge)).valid);

  function nodeLabel(nodeId: string): string {
    const node = nodes.find((candidate) => candidate.id === nodeId);
    return node?.data.name ?? node?.data.type ?? nodeId;
  }

  const onConnect = useCallback(
    (connection: Connection) =>
      setEdges((current) => addEdge({ ...connection, data: {} }, current)),
    [setEdges],
  );

  function addNode(type: string) {
    const index = nodes.length;
    const node: WorkflowNode = {
      id: crypto.randomUUID(),
      type: "workflow",
      // A grid keeps handles reachable; overlapping nodes make connecting edges unreliable.
      position: { x: 80 + (index % 3) * 220, y: 80 + Math.floor(index / 3) * 140 },
      data: { type, name: type },
    };
    setNodes((current) => [...current, node]);
    setSelectedNodeId(node.id);
    setSelectedEdgeId(null);
  }

  function closeEdgeInspector() {
    setSelectedEdgeId(null);
    setEdges((current) =>
      current.map((edge) => (edge.selected ? { ...edge, selected: false } : edge)),
    );
  }

  function updateSelectedConfig(config: Record<string, unknown>) {
    setNodes((current) =>
      current.map((node) =>
        node.id === selectedNodeId ? { ...node, data: { ...node.data, config } } : node,
      ),
    );
  }

  function updateSelectedName(nextName: string) {
    setNodes((current) =>
      current.map((node) =>
        node.id === selectedNodeId ? { ...node, data: { ...node.data, name: nextName } } : node,
      ),
    );
  }

  function updateSelectedCondition(condition: string) {
    if (selectedEdgeId === null) {
      return;
    }
    setEdges((current) =>
      current.map((edge) =>
        edge.id === selectedEdgeId ? { ...edge, data: { ...edge.data, condition } } : edge,
      ),
    );
  }

  function duplicateSelectedEdge() {
    if (selectedEdge === null) {
      return;
    }
    const copy = duplicateEdge(selectedEdge);
    setEdges((current) => [...current, copy]);
    setSelectedEdgeId(copy.id);
    setNotice("Edge duplicated with its condition.");
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "d" && selectedEdge) {
      event.preventDefault();
      duplicateSelectedEdge();
    }
  }

  async function save() {
    setError("");
    setNotice("");
    if (name.trim() === "") {
      setError("Workflow name is required.");
      return;
    }
    if (invalidEdges.length > 0) {
      setError(
        `Fix ${invalidEdges.length} invalid condition${invalidEdges.length === 1 ? "" : "s"} before saving.`,
      );
      return;
    }
    const conditions = [...new Set(edges.map(conditionOf).filter((value) => value.trim() !== ""))];
    setSaving(true);
    try {
      let results;
      try {
        results = await Promise.all(
          conditions.map((condition) => validateWorkflowExpression(condition)),
        );
      } catch {
        setError("Condition validation failed. Check your connection and retry.");
        return;
      }
      const invalid = results.find((result) => !result.valid);
      if (invalid) {
        setError(invalid.errors[0]?.message ?? "A condition is invalid.");
        return;
      }
      try {
        await createWorkflowDefinition({
          name,
          definitionJson: toDefinitionJson(nodes, edges),
        });
        setNotice("Draft saved");
      } catch {
        setError("Save failed");
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <div onKeyDown={onKeyDown}>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Workflow Designer</h1>
      <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
        {Object.keys(nodeColors).map((type) => (
          <button
            key={type}
            type="button"
            onClick={() => addNode(type)}
            style={{
              background: darkenHex(nodeColors[type] ?? "#4f46e5"),
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
      <div style={{ display: "flex", gap: 8, marginBottom: 12, alignItems: "center" }}>
        <input
          placeholder="Workflow name"
          aria-label="Workflow name"
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
        <button type="button" onClick={save} disabled={invalidEdges.length > 0 || saving}>
          Save draft
        </button>
        {invalidEdges.length > 0 ? (
          <span role="status" style={{ color: "#b91c1c", fontSize: 13 }}>
            Fix invalid edge conditions to enable saving.
          </span>
        ) : null}
      </div>

      {error ? <p role="alert">{error}</p> : null}
      {notice ? (
        <p role="status" aria-live="polite">
          {notice}
        </p>
      ) : null}

      <div style={{ display: "flex", gap: 16 }}>
        <div style={{ flex: 1, height: 480, border: "1px solid #e2e8f0", borderRadius: 8 }}>
          <ReactFlow
            nodes={nodes}
            edges={displayEdges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={(_, node) => {
              setSelectedNodeId(node.id);
              setSelectedEdgeId(null);
            }}
            onEdgeClick={(_, edge) => {
              setSelectedEdgeId(edge.id);
              setSelectedNodeId(null);
            }}
            onPaneClick={() => {
              setSelectedNodeId(null);
              closeEdgeInspector();
            }}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
          >
            <Background />
            <Controls />
          </ReactFlow>
        </div>

        {selectedEdge ? (
          <EdgeConditionEditor
            edge={selectedEdge}
            variables={workflowVariables}
            onChangeCondition={updateSelectedCondition}
            onClose={closeEdgeInspector}
            onDuplicate={duplicateSelectedEdge}
          />
        ) : selectedNode ? (
          <div style={{ width: 260, border: "1px solid #e2e8f0", borderRadius: 8, padding: 12 }}>
            <h2 style={{ fontSize: 16, fontWeight: 700 }}>{selectedNode.data.type} node</h2>
            {selectedNode.data.type === "APPROVAL" && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Approvers (one per line, e.g. role:MANAGER)
                <textarea
                  value={((selectedNode.data.config?.approvers as string[]) ?? []).join("\n")}
                  onChange={(event) =>
                    updateSelectedConfig({
                      ...selectedNode.data.config,
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
            {selectedNode.data.type === "FORM" && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Form definition ID
                <input
                  value={(selectedNode.data.config?.formId as string) ?? ""}
                  onChange={(event) =>
                    updateSelectedConfig({
                      ...selectedNode.data.config,
                      formId: event.target.value,
                    })
                  }
                  style={{ width: "100%", display: "block" }}
                />
              </label>
            )}
            {selectedNode.data.type === "TASK" && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Task title
                <input
                  value={selectedNode.data.name ?? ""}
                  onChange={(event) => updateSelectedName(event.target.value)}
                  style={{ width: "100%", display: "block" }}
                />
              </label>
            )}
            {(selectedNode.data.type === "DECISION" ||
              selectedNode.data.type === "CONDITION") && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Fallback guard expression
                <textarea
                  placeholder="#amount > 100"
                  value={(selectedNode.data.config?.expression as string) ?? ""}
                  onChange={(event) =>
                    updateSelectedConfig({
                      ...selectedNode.data.config,
                      expression: event.target.value,
                    })
                  }
                  style={{ width: "100%", minHeight: 64, display: "block" }}
                />
                <span style={{ color: "#6b7280", fontSize: 12 }}>
                  Branch conditions are edited on each outgoing edge. The guard is used only when no
                  edge condition matches.
                </span>
              </label>
            )}
            {selectedNode.data.type === "DELAY" && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Delay seconds
                <input
                  type="number"
                  min={0}
                  value={(selectedNode.data.config?.delaySeconds as number) ?? 0}
                  onChange={(event) =>
                    updateSelectedConfig({
                      ...selectedNode.data.config,
                      delaySeconds: Number(event.target.value),
                    })
                  }
                  style={{ width: "100%", display: "block" }}
                />
              </label>
            )}
            {selectedNode.data.type === "PARALLEL_GATEWAY" && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Join node id
                <input
                  value={(selectedNode.data.config?.joinNodeId as string) ?? ""}
                  onChange={(event) =>
                    updateSelectedConfig({
                      ...selectedNode.data.config,
                      joinNodeId: event.target.value,
                    })
                  }
                  style={{ width: "100%", display: "block" }}
                />
              </label>
            )}
            {selectedNode.data.type === "WEBHOOK" && (
              <>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Target URL
                  <input
                    placeholder="https://hooks.example.com"
                    value={(selectedNode.data.config?.url as string) ?? ""}
                    onChange={(event) =>
                      updateSelectedConfig({
                        ...selectedNode.data.config,
                        url: event.target.value,
                      })
                    }
                    style={{ width: "100%", display: "block" }}
                  />
                </label>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Payload JSON
                  <textarea
                    value={(selectedNode.data.config?.payload as string) ?? "{}"}
                    onChange={(event) =>
                      updateSelectedConfig({
                        ...selectedNode.data.config,
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
                    value={(selectedNode.data.config?.timeoutSeconds as number) ?? 10}
                    onChange={(event) =>
                      updateSelectedConfig({
                        ...selectedNode.data.config,
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
                    value={(selectedNode.data.config?.maxAttempts as number) ?? 3}
                    onChange={(event) =>
                      updateSelectedConfig({
                        ...selectedNode.data.config,
                        maxAttempts: Number(event.target.value),
                      })
                    }
                    style={{ width: "100%", display: "block" }}
                  />
                </label>
              </>
            )}
            {selectedNode.data.type === "SUB_PROCESS" && (
              <>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Child workflow definition ID
                  <input
                    value={(selectedNode.data.config?.workflowDefinitionId as string) ?? ""}
                    onChange={(event) =>
                      updateSelectedConfig({
                        ...selectedNode.data.config,
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
                    value={(selectedNode.data.config?.version as number) ?? ""}
                    onChange={(event) =>
                      updateSelectedConfig({
                        ...selectedNode.data.config,
                        version: event.target.value ? Number(event.target.value) : undefined,
                      })
                    }
                    style={{ width: "100%", display: "block" }}
                  />
                </label>
              </>
            )}
            {(selectedNode.data.type === "START" ||
              selectedNode.data.type === "END" ||
              selectedNode.data.type === "MERGE_GATEWAY") && (
              <p style={{ color: "#6b7280" }}>No configuration for this node type.</p>
            )}
          </div>
        ) : null}
      </div>

      <nav aria-labelledby="designer-transitions-heading" style={{ marginTop: 12 }}>
        <h2 id="designer-transitions-heading" style={{ fontSize: 14, fontWeight: 700 }}>
          Transitions
        </h2>
        {edges.length === 0 ? (
          <p style={{ color: "#6b7280", fontSize: 13 }}>
            No transitions yet. Drag from the source handle of a node to another node.
          </p>
        ) : (
          <ul style={{ listStyle: "none", paddingLeft: 0, display: "grid", gap: 4 }}>
            {edges.map((edge) => {
              const condition = conditionOf(edge);
              const label = `Edit condition from ${nodeLabel(edge.source)} to ${nodeLabel(edge.target)}`;
              return (
                <li key={edge.id}>
                  <button
                    type="button"
                    aria-label={label}
                    onClick={() => {
                      setSelectedEdgeId(edge.id);
                      setSelectedNodeId(null);
                    }}
                  >
                    {nodeLabel(edge.source)} → {nodeLabel(edge.target)}{" "}
                    {condition.trim() === "" ? "(unconditional)" : `(IF: ${condition})`}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </nav>

      <DesignerLegend />
    </div>
  );
}
