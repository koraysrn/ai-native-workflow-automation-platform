"use client";

import { NODE_COLORS, NODE_DRAG_MIME, NODE_TYPES } from "../../lib/workflowDesigner";

interface NodePaletteProps {
  onAdd: (type: string) => void;
}

/**
 * Left-hand palette (issue #198). Every node type is a native drag source for the
 * canvas and a plain button, so pointer, touch and keyboard users all have a path
 * to add a node. The button's accessible name is the node type, matching the
 * existing designer contract.
 */
export default function NodePalette({ onAdd }: NodePaletteProps) {
  return (
    <aside
      aria-labelledby="node-palette-heading"
      style={{ width: 176, flex: "0 0 176px", borderRight: "1px solid #e2e8f0", paddingRight: 12 }}
    >
      <h2 id="node-palette-heading" style={{ fontSize: 14, fontWeight: 700, marginBottom: 6 }}>
        Node palette
      </h2>
      <p
        id="node-palette-help"
        style={{ color: "#374151", fontSize: 12, marginBottom: 10, marginTop: 0 }}
      >
        Drag a node type onto the canvas, or press its button to add it at the next free position.
      </p>
      <ul
        aria-describedby="node-palette-help"
        style={{ listStyle: "none", paddingLeft: 0, margin: 0, display: "grid", gap: 6 }}
      >
        {NODE_TYPES.map((type) => (
          <li key={type}>
            <button
              type="button"
              draggable
              aria-label={type}
              aria-describedby="node-palette-help"
              onClick={() => onAdd(type)}
              onDragStart={(event) => {
                event.dataTransfer.setData(NODE_DRAG_MIME, type);
                event.dataTransfer.setData("text/plain", type);
                event.dataTransfer.effectAllowed = "copy";
              }}
              style={{
                background: darken(NODE_COLORS[type] ?? "#4f46e5"),
                color: "#fff",
                border: "none",
                padding: "6px 10px",
                borderRadius: 6,
                width: "100%",
                textAlign: "left",
                cursor: "grab",
              }}
            >
              {type}
            </button>
          </li>
        ))}
      </ul>
    </aside>
  );
}

/** Darkens a palette colour so the white label keeps a WCAG AA contrast ratio. */
export function darken(hex: string, factor = 0.6): string {
  const value = Number.parseInt(hex.replace("#", ""), 16);
  const channels = [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff].map((channel) =>
    Math.round(channel * factor)
      .toString(16)
      .padStart(2, "0"),
  );
  return `#${channels.join("")}`;
}
