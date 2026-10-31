import { render, screen } from "@testing-library/react";
import DesignerLegend from "../DesignerLegend";

describe("DesignerLegend", () => {
  it("explains unconditional and conditional transitions", () => {
    render(<DesignerLegend />);

    expect(screen.getByRole("heading", { name: "Legend" })).toBeInTheDocument();
    expect(screen.getByText("Unconditional transition")).toBeInTheDocument();
    expect(
      screen.getByText(/Conditional transition evaluated as a SpEL expression/),
    ).toBeInTheDocument();
    expect(screen.getByText("IF")).toBeInTheDocument();
  });
});
