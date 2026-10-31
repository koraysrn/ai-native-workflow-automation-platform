import {
  conditionOf,
  duplicateEdge,
  isConditional,
  parseDefinitionJson,
  toDefinitionJson,
  withCondition,
  withConditionIndicators,
  type WorkflowEdge,
  type WorkflowNode,
} from "../workflowGraph";

function node(overrides: Partial<WorkflowNode> = {}): WorkflowNode {
  return {
    id: "start",
    type: "workflow",
    position: { x: 0, y: 0 },
    data: { type: "START", name: "Start" },
    ...overrides,
  };
}

function edge(overrides: Partial<WorkflowEdge> = {}): WorkflowEdge {
  return {
    id: "e1",
    source: "start",
    target: "decision",
    data: { condition: "#amount > 100" },
    ...overrides,
  };
}

describe("conditionOf", () => {
  it("returns the exact condition text", () => {
    expect(conditionOf(edge({ data: { condition: "  #amount > 100  " } }))).toBe(
      "  #amount > 100  ",
    );
  });

  it("treats a missing or non-string value as unconditional", () => {
    expect(conditionOf(undefined)).toBe("");
    expect(conditionOf({ data: {} })).toBe("");
    expect(conditionOf({ data: { condition: 42 } })).toBe("");
    expect(isConditional({ data: { condition: "   " } })).toBe(false);
  });

  it("stores a condition verbatim", () => {
    expect(withCondition(edge(), "\n#status == 'OK'\n").data?.condition).toBe(
      "\n#status == 'OK'\n",
    );
  });
});

describe("toDefinitionJson", () => {
  it("stores the condition on the transition and omits blank conditions", () => {
    const nodes = [
      node(),
      node({ id: "decision", data: { type: "DECISION", name: "Decide" } }),
      node({ id: "end", data: { type: "END", name: "End" } }),
    ];
    const edges = [
      edge({ id: "high", source: "decision", target: "end", data: { condition: " #amount >= 100 " } }),
      edge({ id: "unconditional", source: "start", target: "decision", data: { condition: "   " } }),
    ];

    const parsed = JSON.parse(toDefinitionJson(nodes, edges)) as {
      transitions: Array<Record<string, unknown>>;
    };

    expect(parsed.transitions[0]).toEqual({
      id: "high",
      from: "decision",
      to: "end",
      condition: " #amount >= 100 ",
    });
    expect(parsed.transitions[1]).not.toHaveProperty("condition");
  });

  it("preserves node config and names in the workflow JSON", () => {
    const parsed = JSON.parse(
      toDefinitionJson(
        [node({ data: { type: "TASK", name: "Review", config: { approvers: ["role:MANAGER"] } } })],
        [],
      ),
    ) as { nodes: Array<Record<string, unknown>> };

    expect(parsed.nodes[0]).toEqual({
      id: "start",
      type: "TASK",
      name: "Review",
      config: { approvers: ["role:MANAGER"] },
    });
  });
});

describe("parseDefinitionJson", () => {
  it("rebuilds edges with the exact condition text", () => {
    const graph = parseDefinitionJson(
      JSON.stringify({
        nodes: [
          { id: "start", type: "START" },
          { id: "decision", type: "DECISION" },
        ],
        transitions: [
          { id: "t1", from: "start", to: "decision" },
          { id: "t2", from: "decision", to: "start", condition: "#amount > 100" },
        ],
      }),
    );

    expect(graph.nodes).toHaveLength(2);
    expect(graph.nodes[1]?.data).toMatchObject({ type: "DECISION", name: "DECISION" });
    expect(graph.edges[0]?.data?.condition).toBeUndefined();
    expect(graph.edges[1]?.data?.condition).toBe("#amount > 100");
  });

  it("accepts the legacy edges key and skips malformed transitions", () => {
    const graph = parseDefinitionJson(
      JSON.stringify({
        nodes: [{ id: "a", type: "START" }],
        edges: [{ from: "a", to: "b", condition: "#x" }, { from: "a" }],
      }),
    );

    expect(graph.edges).toHaveLength(1);
    expect(graph.edges[0]?.data?.condition).toBe("#x");
  });

  it("round-trips a condition without rewriting its text", () => {
    const original = JSON.stringify({
      nodes: [{ id: "a", type: "START" }, { id: "b", type: "END" }],
      transitions: [{ id: "t1", from: "a", to: "b", condition: "  #x == 'A' || #y  " }],
    });
    const graph = parseDefinitionJson(original);
    const roundTripped = parseDefinitionJson(toDefinitionJson(graph.nodes, graph.edges));

    expect(roundTripped.edges[0]?.data?.condition).toBe("  #x == 'A' || #y  ");
  });

  it("rejects a snapshot without nodes", () => {
    expect(() => parseDefinitionJson("{}")).toThrow(/nodes array/);
    expect(() => parseDefinitionJson(JSON.stringify({ nodes: [{}] }))).toThrow(/id and a type/);
  });
});

describe("duplicateEdge", () => {
  it("creates a new id and keeps the exact condition", () => {
    const copy = duplicateEdge(edge({ data: { condition: "\t#role == 'MANAGER'\t" } }));

    expect(copy.id).not.toBe("e1");
    expect(copy.source).toBe("start");
    expect(copy.target).toBe("decision");
    expect(copy.data?.condition).toBe("\t#role == 'MANAGER'\t");
    expect(copy.selected).toBe(false);
  });
});

describe("withConditionIndicators", () => {
  it("marks only conditional edges with the custom type", () => {
    const [conditional, plain] = withConditionIndicators([
      edge(),
      edge({ id: "e2", data: { condition: "   " } }),
    ]);

    expect(conditional?.type).toBe("conditional");
    expect(plain?.type).toBe("default");
  });
});
