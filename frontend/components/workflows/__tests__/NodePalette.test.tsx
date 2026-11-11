import { fireEvent, render, screen } from "@testing-library/react";

import { NODE_DRAG_MIME, NODE_TYPES } from "../../../lib/workflowDesigner";
import NodePalette, { darken } from "../NodePalette";

describe("NodePalette", () => {
  it("renders every supported node type as a named button", () => {
    render(<NodePalette onAdd={jest.fn()} />);

    expect(screen.getByRole("heading", { name: "Node palette" })).toBeInTheDocument();
    expect(screen.getByText(/Drag a node type onto the canvas/)).toBeInTheDocument();
    for (const type of NODE_TYPES) {
      expect(screen.getByRole("button", { name: type })).toBeInTheDocument();
    }
    expect(screen.getAllByRole("button")).toHaveLength(NODE_TYPES.length);
  });

  it("adds a node when a palette button is pressed", () => {
    const onAdd = jest.fn();
    render(<NodePalette onAdd={onAdd} />);

    fireEvent.click(screen.getByRole("button", { name: "APPROVAL" }));

    expect(onAdd).toHaveBeenCalledWith("APPROVAL");
  });

  it("writes the node type and the plain-text fallback into the drag data", () => {
    const setData = jest.fn();
    render(<NodePalette onAdd={jest.fn()} />);
    const dataTransfer = { setData, effectAllowed: "none" } as unknown as DataTransfer;

    fireEvent.dragStart(screen.getByRole("button", { name: "TASK" }), { dataTransfer });

    expect(setData).toHaveBeenCalledWith(NODE_DRAG_MIME, "TASK");
    expect(setData).toHaveBeenCalledWith("text/plain", "TASK");
    expect(dataTransfer.effectAllowed).toBe("copy");
  });

  it("darkens palette colours so the white label keeps its contrast", () => {
    expect(darken("#ffffff")).toBe("#999999");
    expect(darken("#10b981")).toBe("#0a6f4d");
  });
});
