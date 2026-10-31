/**
 * Explains the transition decorations so users can tell conditional branches from unconditional
 * ones without opening the inspector.
 */
export default function DesignerLegend() {
  return (
    <section aria-labelledby="designer-legend-heading" style={{ marginTop: 8 }}>
      <h2 id="designer-legend-heading" style={{ fontSize: 14, fontWeight: 700 }}>
        Legend
      </h2>
      <ul
        style={{
          listStyle: "none",
          paddingLeft: 0,
          display: "flex",
          gap: 16,
          flexWrap: "wrap",
          fontSize: 13,
        }}
      >
        <li>
          <span
            aria-hidden="true"
            style={{
              display: "inline-block",
              width: 32,
              borderTop: "2px solid #94a3b8",
              verticalAlign: "middle",
              marginRight: 6,
            }}
          />
          Unconditional transition
        </li>
        <li>
          <span aria-hidden="true" style={badgeStyle}>
            IF
          </span>
          Conditional transition evaluated as a SpEL expression
        </li>
      </ul>
    </section>
  );
}

const badgeStyle = {
  display: "inline-block",
  background: "#e0f2fe",
  color: "#0c4a6e",
  border: "1px solid #0ea5e9",
  borderRadius: 999,
  padding: "0 6px",
  marginRight: 6,
  fontSize: 11,
  fontWeight: 700,
} as const;
