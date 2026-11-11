import type { Connection } from "@xyflow/react";

import {
  NODE_COLORS,
  NODE_TYPES,
  addConnection,
  canRedo,
  canUndo,
  connectionError,
  createHistory,
  createNode,
  createsCycle,
  dropToFlowPosition,
  duplicateNode,
  pushHistory,
  redo,
  removeNode,
  shortcutAction,
  undo,
  validateGraph,
} from "../workflowDesigner";
import type { WorkflowEdge, WorkflowNode } from "../workflowGraph";

function node(id: string, type: string, config?: Record<string, unknown>): WorkflowNode {
  return {
    id,
    type: "workflow",
    position: { x: 0, y: 0 },
    data: { type, name: type, ...(config ? { config } : {}) },
  };
}

function edge(id: string, source: string, target: string): WorkflowEdge {
  return { id, source, target, data: {} };
}

function connection(source: string, target: string): Connection {
  return { source, target, sourceHandle: null, targetHandle: null };
}

function ids(...values: string[]): () => string {
  let index = 0;
  return () => values[index++] ?? `fallback-${index}`;
}

describe("createNode", () => {
  it.each(NODE_TYPES)("creates a %s node with the palette position", (type) => {
    const created = createNode(type, { x: 12, y: 34 }, { newId: () => "node-1" });
    expect(created.id).toBe("node-1");
    expect(created.type).toBe("workflow");
    expect(created.position).toEqual({ x: 12, y: 34 });
    expect(created.data).toEqual({ type, name: type });
    expect(NODE_COLORS[type]).toMatch(/^#[0-9a-f]{6}$/);
  });

  it("regenerates an id that already exists", () => {
    const created = createNode(
      "TASK",
      { x: 0, y: 0 },
      { existingIds: ["a"], newId: ids("a", "b") },
    );
    expect(created.id).toBe("b");
  });

  it("fails instead of looping forever when every generated id collides", () => {
    expect(() =>
      createNode("TASK", { x: 0, y: 0 }, { existingIds: ["a"], newId: () => "a" }),
    ).toThrow(/unique node id/);
  });
});

describe("dropToFlowPosition", () => {
  const bounds = { left: 100, top: 100, right: 500, bottom: 400 };
  const convert = (position: { x: number; y: number }) => ({
    x: position.x - 100,
    y: position.y - 100,
  });

  it("converts a client drop to canvas coordinates", () => {
    expect(
      dropToFlowPosition({ clientX: 260, clientY: 240, bounds, screenToFlowPosition: convert }),
    ).toEqual({ x: 160, y: 140 });
  });

  it.each([
    ["left", 99, 200],
    ["right", 501, 200],
    ["above", 200, 99],
    ["below", 200, 401],
  ])("ignores a drop %s of the canvas", (_name, clientX, clientY) => {
    expect(
      dropToFlowPosition({ clientX, clientY, bounds, screenToFlowPosition: convert }),
    ).toBeNull();
  });

  it("ignores a non-finite conversion", () => {
    expect(
      dropToFlowPosition({
        clientX: 200,
        clientY: 200,
        bounds,
        screenToFlowPosition: () => ({ x: Number.NaN, y: 10 }),
      }),
    ).toBeNull();
  });
});

describe("connection validation", () => {
  const nodes = [node("start", "START"), node("task", "TASK"), node("end", "END")];

  it("detects self-loops and cycles", () => {
    expect(createsCycle([], "a", "a")).toBe(true);
    expect(createsCycle([edge("1", "a", "b")], "b", "a")).toBe(true);
    expect(createsCycle([edge("1", "a", "b")], "b", "c")).toBe(false);
    expect(createsCycle([edge("1", "a", "b"), edge("2", "c", "d")], "b", "c")).toBe(false);
  });

  it("rejects missing endpoints, unknown nodes, duplicates and cycles", () => {
    expect(
      connectionError(nodes, [], {
        source: "",
        target: "task",
        sourceHandle: null,
        targetHandle: null,
      }),
    ).toMatch(/source/);
    expect(connectionError(nodes, [], connection("ghost", "task"))).toMatch(/must exist/);
    expect(
      connectionError(nodes, [edge("1", "start", "task")], connection("start", "task")),
    ).toMatch(/already exists/);
    expect(
      connectionError(
        nodes,
        [edge("1", "start", "task"), edge("2", "task", "end")],
        connection("end", "start"),
      ),
    ).toMatch(/cycle/);
    expect(connectionError(nodes, [], connection("start", "task"))).toBeNull();
  });

  it("adds a valid connection and keeps the edges unchanged for an invalid one", () => {
    const edges = [edge("1", "start", "task")];
    const added = addConnection(nodes, edges, connection("task", "end"), () => "edge-2");
    expect(added.error).toBeNull();
    expect(added.edges).toHaveLength(2);
    expect(added.edges[1]).toMatchObject({ id: "edge-2", source: "task", target: "end" });

    const rejected = addConnection(nodes, edges, connection("task", "start"), () => "edge-3");
    expect(rejected.error).toMatch(/cycle/);
    expect(rejected.edges).toBe(edges);
  });
});

describe("removeNode and duplicateNode", () => {
  it("removes a node together with its transitions", () => {
    const result = removeNode(
      [node("a", "START"), node("b", "TASK"), node("c", "END")],
      [edge("1", "a", "b"), edge("2", "b", "c"), edge("3", "c", "b"), edge("4", "a", "c")],
      "b",
    );
    expect(result.nodes.map((candidate) => candidate.id)).toEqual(["a", "c"]);
    expect(result.edges.map((candidate) => candidate.id)).toEqual(["4"]);
  });

  it("duplicates a node with a new id, offset position and copied config", () => {
    const original = node("a", "APPROVAL", { approvers: ["role:MANAGER"], nested: { x: 1 } });
    const copy = duplicateNode([original], "a", () => "b");
    expect(copy).not.toBeNull();
    expect(copy?.id).toBe("b");
    expect(copy?.position).toEqual({ x: 40, y: 40 });
    expect(copy?.data.config).toEqual({ approvers: ["role:MANAGER"], nested: { x: 1 } });
    expect(copy?.data.config).not.toBe(original.data.config);
  });

  it("returns null for an unknown node", () => {
    expect(duplicateNode([], "ghost", () => "b")).toBeNull();
  });
});

describe("validateGraph", () => {
  it("rejects an empty graph", () => {
    const result = validateGraph([], []);
    expect(result.valid).toBe(false);
    expect(result.errors).toEqual([
      "A workflow needs exactly one START node.",
      "A workflow needs at least one END node.",
    ]);
  });

  it("rejects duplicate START nodes and a missing END", () => {
    const result = validateGraph([node("a", "START"), node("b", "START")], []);
    expect(result.errors).toContain("A workflow needs exactly one START node.");
    expect(result.errors).toContain("A workflow needs at least one END node.");
  });

  it("rejects dangling transitions", () => {
    const result = validateGraph([node("a", "START"), node("b", "END")], [edge("1", "a", "ghost")]);
    expect(result.errors).toContain("1 transition(s) point to a missing node.");
  });

  it("rejects cycles", () => {
    const result = validateGraph(
      [node("a", "START"), node("b", "TASK"), node("c", "END")],
      [edge("1", "a", "b"), edge("2", "b", "a")],
    );
    expect(result.errors).toContain("The workflow contains a cycle.");
  });

  it("rejects nodes that are unreachable from START", () => {
    const result = validateGraph(
      [node("a", "START"), node("b", "END"), node("c", "TASK")],
      [edge("1", "a", "b")],
    );
    expect(result.errors.some((error) => error.includes("Unreachable: TASK"))).toBe(true);
  });

  it("accepts a connected START -> END flow", () => {
    const result = validateGraph(
      [node("a", "START"), node("b", "TASK"), node("c", "END")],
      [edge("1", "a", "b"), edge("2", "b", "c")],
    );
    expect(result).toEqual({ valid: true, errors: [] });
  });
});

describe("history", () => {
  const empty = { nodes: [], edges: [] };
  const one = { nodes: [node("a", "START")], edges: [] };
  const two = { nodes: [node("a", "START"), node("b", "END")], edges: [] };

  it("walks snapshots with undo and redo", () => {
    let history = createHistory(empty);
    history = pushHistory(history, one);
    history = pushHistory(history, two);
    expect(canUndo(history)).toBe(true);
    expect(canRedo(history)).toBe(false);

    const undone = undo(history);
    expect(undone.snapshot).toEqual(one);
    expect(canRedo(undone.history)).toBe(true);

    const redone = redo(undone.history);
    expect(redone.snapshot).toEqual(two);
  });

  it("does not move past the first or last snapshot", () => {
    let history = createHistory(empty);
    expect(canUndo(history)).toBe(false);
    expect(undo(history).snapshot).toEqual(empty);
    expect(redo(history).snapshot).toEqual(empty);

    history = pushHistory(history, one);
    expect(redo(history).snapshot).toEqual(one);
  });

  it("skips no-op snapshots and drops the redo tail after a new edit", () => {
    let history = createHistory(empty);
    history = pushHistory(history, one);
    const unchanged = pushHistory(history, one);
    expect(unchanged).toBe(history);

    history = pushHistory(history, two);
    history = undo(history).history;
    history = pushHistory(history, { nodes: [node("c", "END")], edges: [] });
    expect(canRedo(history)).toBe(false);
    expect(history.snapshots).toHaveLength(3);
  });

  it("trims the oldest snapshots beyond the limit", () => {
    let history = createHistory(empty);
    for (let index = 0; index < 5; index++) {
      history = pushHistory(history, { nodes: [node(`${index}`, "TASK")], edges: [] }, 3);
    }
    expect(history.snapshots).toHaveLength(3);
    expect(history.index).toBe(2);
  });
});

describe("shortcutAction", () => {
  const selection = { node: true, edge: false };

  it("maps undo, redo, duplicate and delete", () => {
    expect(shortcutAction({ key: "z", ctrlKey: true }, selection)).toBe("undo");
    expect(shortcutAction({ key: "z", metaKey: true }, selection)).toBe("undo");
    expect(shortcutAction({ key: "Z", ctrlKey: true, shiftKey: true }, selection)).toBe("redo");
    expect(shortcutAction({ key: "y", ctrlKey: true }, selection)).toBe("redo");
    expect(shortcutAction({ key: "d", metaKey: true }, { node: false, edge: true })).toBe(
      "duplicate",
    );
    expect(shortcutAction({ key: "Delete" }, selection)).toBe("delete");
    expect(shortcutAction({ key: "Backspace" }, { node: false, edge: true })).toBe("delete");
  });

  it("ignores shortcuts without a selection or without a modifier", () => {
    expect(shortcutAction({ key: "d", ctrlKey: true }, { node: false, edge: false })).toBeNull();
    expect(shortcutAction({ key: "z" }, selection)).toBeNull();
    expect(shortcutAction({ key: "Delete" }, { node: false, edge: false })).toBeNull();
    expect(shortcutAction({ key: "x", ctrlKey: true }, selection)).toBeNull();
  });
});
