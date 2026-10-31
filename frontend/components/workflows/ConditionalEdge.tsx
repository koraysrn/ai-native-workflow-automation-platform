"use client";

import {
  BaseEdge,
  EdgeLabelRenderer,
  getBezierPath,
  type EdgeProps,
} from "@xyflow/react";
import { conditionOf } from "../../lib/workflowGraph";

const MAX_BADGE_LENGTH = 40;

/**
 * Edge renderer that marks a transition as conditional. The label is React text, never HTML, so a
 * condition containing markup is displayed verbatim instead of being evaluated by the browser.
 */
export default function ConditionalEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
  selected,
}: EdgeProps) {
  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });
  const condition = conditionOf({ data });
  const display =
    condition.length > MAX_BADGE_LENGTH ? `${condition.slice(0, MAX_BADGE_LENGTH)}…` : condition;

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        style={{ stroke: "#0ea5e9", strokeWidth: selected ? 3 : 2 }}
      />
      <EdgeLabelRenderer>
        <div
          data-testid="condition-badge"
          title={condition}
          aria-label={`Conditional transition: ${condition}`}
          className="nodrag nopan"
          style={{
            position: "absolute",
            transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
            background: "#e0f2fe",
            color: "#0c4a6e",
            border: "1px solid #0ea5e9",
            borderRadius: 999,
            padding: "2px 8px",
            fontSize: 12,
            pointerEvents: "all",
            maxWidth: 240,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          IF: {display}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}
