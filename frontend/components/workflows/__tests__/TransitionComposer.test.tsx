import { fireEvent, render, screen } from "@testing-library/react";

import type { WorkflowNode } from "../../../lib/workflowGraph";
import TransitionComposer from "../TransitionComposer";

const nodes: WorkflowNode[] = [
  {
    id: "aaaaaaaa-1",
    type: "workflow",
    position: { x: 0, y: 0 },
    data: { type: "START", name: "START" },
  },
  {
    id: "bbbbbbbb-2",
    type: "workflow",
    position: { x: 0, y: 0 },
    data: { type: "END", name: "END" },
  },
];

describe("TransitionComposer", () => {
  it("disables the submit button until both endpoints are selected", () => {
    render(<TransitionComposer nodes={nodes} onAdd={jest.fn()} />);
    const submit = screen.getByRole("button", { name: "Add transition" });

    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Transition source"), {
      target: { value: "aaaaaaaa-1" },
    });
    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Transition target"), {
      target: { value: "bbbbbbbb-2" },
    });
    expect(submit).toBeEnabled();
  });

  it("adds the selected transition", () => {
    const onAdd = jest.fn();
    render(<TransitionComposer nodes={nodes} onAdd={onAdd} />);

    fireEvent.change(screen.getByLabelText("Transition source"), {
      target: { value: "aaaaaaaa-1" },
    });
    fireEvent.change(screen.getByLabelText("Transition target"), {
      target: { value: "bbbbbbbb-2" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add transition" }));

    expect(onAdd).toHaveBeenCalledWith("aaaaaaaa-1", "bbbbbbbb-2");
  });

  it("lists every node with a stable label", () => {
    render(<TransitionComposer nodes={nodes} onAdd={jest.fn()} />);

    const source = screen.getByLabelText("Transition source");
    expect(source).toHaveTextContent("START (aaaaaaaa)");
    expect(source).toHaveTextContent("END (bbbbbbbb)");
  });

  it("announces a rejected transition", () => {
    render(
      <TransitionComposer
        nodes={nodes}
        onAdd={jest.fn()}
        error="This transition would create a cycle."
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("cycle");
  });
});
