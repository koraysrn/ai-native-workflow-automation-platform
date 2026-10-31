import type { ReactNode } from "react";
import { render, screen } from "@testing-library/react";
import { Position } from "@xyflow/react";
import ConditionalEdge from "../ConditionalEdge";

jest.mock("@xyflow/react", () => {
  const actual = jest.requireActual("@xyflow/react");
  return {
    ...actual,
    BaseEdge: () => null,
    EdgeLabelRenderer: ({ children }: { children: ReactNode }) => children,
  };
});

const baseProps = {
  id: "e1",
  source: "start",
  target: "end",
  sourceX: 0,
  sourceY: 0,
  targetX: 100,
  targetY: 100,
  sourcePosition: Position.Bottom,
  targetPosition: Position.Top,
  selected: false,
};

describe("ConditionalEdge", () => {
  it("renders the IF badge with the condition text", () => {
    render(<ConditionalEdge {...baseProps} data={{ condition: "#amount > 100" }} />);

    const badge = screen.getByTestId("condition-badge");
    expect(badge).toHaveTextContent("IF: #amount > 100");
    expect(badge).toHaveAttribute("title", "#amount > 100");
    expect(badge).toHaveAttribute("aria-label", "Conditional transition: #amount > 100");
  });

  it("keeps markup in a condition escaped", () => {
    const condition = "#name == '<img src=x onerror=alert(1)>'";
    const { container } = render(<ConditionalEdge {...baseProps} data={{ condition }} />);

    expect(screen.getByTestId("condition-badge")).toHaveTextContent(condition);
    expect(container.querySelector("img")).toBeNull();
  });

  it("truncates a long condition in the badge while keeping the full title", () => {
    const condition = "#amount > 100 && #role == 'MANAGER' && #status == 'APPROVED'";
    render(<ConditionalEdge {...baseProps} data={{ condition }} />);

    const badge = screen.getByTestId("condition-badge");
    expect(badge.textContent).toContain("…");
    expect(badge).toHaveAttribute("title", condition);
  });

  it("renders an empty badge when the condition is missing", () => {
    render(<ConditionalEdge {...baseProps} />);

    expect(screen.getByTestId("condition-badge")).toHaveTextContent("IF:");
  });
});
