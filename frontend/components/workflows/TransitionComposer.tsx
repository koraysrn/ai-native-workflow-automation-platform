"use client";

import { useState, type FormEvent } from "react";

import type { WorkflowNode } from "../../lib/workflowGraph";

interface TransitionComposerProps {
  nodes: WorkflowNode[];
  onAdd: (source: string, target: string) => void;
  error?: string | null;
}

function nodeLabel(node: WorkflowNode): string {
  return `${node.data.name ?? node.data.type} (${node.id.slice(0, 8)})`;
}

/**
 * Keyboard-only way to connect two nodes (issue #198). It mirrors the drag
 * connection so screen-reader and keyboard users never need a pointer.
 */
export default function TransitionComposer({
  nodes,
  onAdd,
  error = null,
}: TransitionComposerProps) {
  const [source, setSource] = useState("");
  const [target, setTarget] = useState("");

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (source === "" || target === "") {
      return;
    }
    onAdd(source, target);
  }

  return (
    <form
      onSubmit={submit}
      aria-labelledby="transition-composer-heading"
      style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "end", marginTop: 8 }}
    >
      <h2
        id="transition-composer-heading"
        style={{ width: "100%", fontSize: 14, fontWeight: 700, margin: 0 }}
      >
        Add a transition
      </h2>
      <label style={{ display: "grid", gap: 2, fontSize: 13 }}>
        From
        <select
          aria-label="Transition source"
          value={source}
          onChange={(event) => setSource(event.target.value)}
        >
          <option value="">Select a node</option>
          {nodes.map((node) => (
            <option key={node.id} value={node.id}>
              {nodeLabel(node)}
            </option>
          ))}
        </select>
      </label>
      <label style={{ display: "grid", gap: 2, fontSize: 13 }}>
        To
        <select
          aria-label="Transition target"
          value={target}
          onChange={(event) => setTarget(event.target.value)}
        >
          <option value="">Select a node</option>
          {nodes.map((node) => (
            <option key={node.id} value={node.id}>
              {nodeLabel(node)}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={source === "" || target === ""}>
        Add transition
      </button>
      {error ? <p role="alert">{error}</p> : null}
    </form>
  );
}
