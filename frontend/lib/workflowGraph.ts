import type { Edge, Node } from "@xyflow/react";

/** Node payload used by the designer (mirrors `WorkflowNodeData` in the designer page). */
export type WorkflowNodeData = {
  type: string;
  name?: string;
  config?: Record<string, unknown>;
};

/** Edge payload. The condition is stored verbatim so copy/paste and reloads never rewrite it. */
export type WorkflowEdgeData = Record<string, unknown> & { condition?: string };

export type WorkflowNode = Node<WorkflowNodeData>;
export type WorkflowEdge = Edge<WorkflowEdgeData>;

export interface SerializedTransition {
  id: string;
  from: string;
  to: string;
  condition?: string;
}

export interface ParsedWorkflowGraph {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function newEdgeId(): string {
  const random = globalThis.crypto?.randomUUID?.();
  return random ?? `edge-${Math.random().toString(36).slice(2)}`;
}

/** Returns the exact edge condition text; a missing or non-string value is unconditional. */
export function conditionOf(
  edge: { data?: Record<string, unknown> | undefined } | null | undefined,
): string {
  const value = edge?.data?.condition;
  return typeof value === "string" ? value : "";
}

/** True when the edge carries a non-blank condition. */
export function isConditional(
  edge: { data?: Record<string, unknown> | undefined } | null | undefined,
): boolean {
  return conditionOf(edge).trim() !== "";
}

/** Returns a copy of the edge with the condition stored verbatim (no trimming). */
export function withCondition(edge: WorkflowEdge, condition: string): WorkflowEdge {
  return { ...edge, data: { ...edge.data, condition } };
}

/**
 * Serializes the designer graph to the workflow-definition JSON consumed by the backend.
 *
 * The condition text is emitted verbatim and omitted when blank, so an unconditional transition
 * never grows a `condition` key and a conditional transition keeps its exact spelling.
 */
export function toDefinitionJson(nodes: WorkflowNode[], edges: WorkflowEdge[]): string {
  return JSON.stringify({
    nodes: nodes.map((node) => {
      const config = node.data.config;
      return {
        id: node.id,
        type: node.data.type,
        name: node.data.name ?? node.data.type,
        ...(config && Object.keys(config).length > 0 ? { config } : {}),
      };
    }),
    transitions: edges.map((edge, index) => {
      const condition = conditionOf(edge);
      const transition: SerializedTransition = {
        id: edge.id === "" ? `transition-${index}` : edge.id,
        from: edge.source,
        to: edge.target,
        ...(condition.trim() === "" ? {} : { condition }),
      };
      return transition;
    }),
  });
}

/**
 * Rebuilds the designer graph from a workflow-definition JSON snapshot.
 *
 * This is the reload path for conditions: transitions (or legacy `edges`) with a `condition`
 * become React Flow edges whose `data.condition` keeps the exact text.
 */
export function parseDefinitionJson(json: string): ParsedWorkflowGraph {
  const parsed: unknown = JSON.parse(json);
  if (!isRecord(parsed) || !Array.isArray(parsed.nodes)) {
    throw new Error("Workflow definition must contain a nodes array.");
  }

  const nodes: WorkflowNode[] = parsed.nodes.map((raw, index) => {
    if (!isRecord(raw)) {
      throw new Error("Every workflow node must be an object.");
    }
    const id = asString(raw.id);
    const type = asString(raw.type);
    if (id === null || type === null) {
      throw new Error("Every workflow node needs an id and a type.");
    }
    const name = asString(raw.name);
    const config = isRecord(raw.config) ? raw.config : undefined;
    return {
      id,
      type: "workflow",
      position: { x: 80 + index * 40, y: 80 + index * 40 },
      data: {
        type,
        name: name ?? type,
        ...(config ? { config } : {}),
      },
    };
  });

  const rawTransitions = Array.isArray(parsed.transitions)
    ? parsed.transitions
    : Array.isArray(parsed.edges)
      ? parsed.edges
      : [];

  const edges: WorkflowEdge[] = rawTransitions.flatMap((raw, index) => {
    if (!isRecord(raw)) {
      return [];
    }
    const from = asString(raw.from);
    const to = asString(raw.to);
    if (from === null || to === null) {
      return [];
    }
    const condition = asString(raw.condition);
    const id = asString(raw.id) ?? `transition-${index}`;
    const edge: WorkflowEdge = {
      id,
      source: from,
      target: to,
      data: condition === null ? {} : { condition },
    };
    return [edge];
  });

  return { nodes, edges };
}

/** Duplicates an edge, deep-copying its data so the exact condition text survives copy/paste. */
export function duplicateEdge(edge: WorkflowEdge): WorkflowEdge {
  return {
    ...edge,
    id: newEdgeId(),
    selected: false,
    data: { ...edge.data },
  };
}

/**
 * Marks conditional edges with the custom `conditional` type so React Flow renders the IF badge.
 * Unconditional edges stay on the default type.
 */
export function withConditionIndicators(edges: WorkflowEdge[]): WorkflowEdge[] {
  return edges.map((edge) => ({
    ...edge,
    type: isConditional(edge) ? "conditional" : "default",
    data: { ...edge.data },
  }));
}
