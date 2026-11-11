import { render, screen } from "@testing-library/react";
import { ApiError } from "../../../lib/api";
import { EmptyState, ErrorState, LoadingState, QueryState } from "../QueryState";

describe("QueryState components", () => {
  it("renders the standard loading state", () => {
    render(<LoadingState label="Loading tasks…" />);
    expect(screen.getByTestId("loading-state")).toHaveTextContent("Loading tasks…");
    expect(screen.getByRole("status")).toHaveAttribute("aria-live", "polite");
  });

  it("renders a generic error state", () => {
    render(<ErrorState error={new ApiError(500, "boom")} message="Failed to load tasks." />);
    expect(screen.getByRole("alert")).toHaveTextContent("Failed to load tasks.");
    expect(screen.queryByTestId("forbidden-state")).not.toBeInTheDocument();
  });

  it("renders the forbidden variant for a 403", () => {
    render(<ErrorState error={new ApiError(403, "nope")} />);
    expect(screen.getByTestId("forbidden-state")).toHaveTextContent(
      "You do not have permission to view this.",
    );
  });

  it("renders an empty state with the default test id", () => {
    render(<EmptyState message="Nothing here yet." />);
    expect(screen.getByTestId("empty-state")).toHaveTextContent("Nothing here yet.");
  });

  it("maps the query lifecycle to the right branch", () => {
    const { rerender } = render(
      <QueryState isLoading isError={false} loadingLabel="Loading…" emptyMessage="Empty">
        <p>content</p>
      </QueryState>,
    );
    expect(screen.getByTestId("loading-state")).toBeInTheDocument();

    rerender(
      <QueryState isLoading={false} isError error={new ApiError(500, "boom")} emptyMessage="Empty">
        <p>content</p>
      </QueryState>,
    );
    expect(screen.getByTestId("error-state")).toBeInTheDocument();

    rerender(
      <QueryState isLoading={false} isError={false} isEmpty emptyMessage="Empty">
        <p>content</p>
      </QueryState>,
    );
    expect(screen.getByTestId("empty-state")).toHaveTextContent("Empty");

    rerender(
      <QueryState isLoading={false} isError={false} isEmpty={false} emptyMessage="Empty">
        <p>content</p>
      </QueryState>,
    );
    expect(screen.getByText("content")).toBeInTheDocument();
  });
});
