import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import * as api from "../../../lib/api";
import { queryKeys } from "../../../lib/queryKeys";
import AnalyticsPage from "../analytics/page";
import OrganizationsPage from "../organizations/page";
import TasksPage from "../tasks/page";

jest.mock("../../../lib/api", () => {
  class ApiError extends Error {
    readonly status: number;
    constructor(status: number, message: string) {
      super(message);
      this.name = "ApiError";
      this.status = status;
    }
  }
  return {
    ApiError,
    errorStatus: (error: unknown) => (error instanceof ApiError ? error.status : undefined),
    fetchAnalyticsSummary: jest.fn(),
    listTasks: jest.fn(),
    fetchOrganizationTree: jest.fn(),
  };
});

const fetchAnalyticsSummary = api.fetchAnalyticsSummary as jest.MockedFunction<
  typeof api.fetchAnalyticsSummary
>;
const listTasks = api.listTasks as jest.MockedFunction<typeof api.listTasks>;
const fetchOrganizationTree = api.fetchOrganizationTree as jest.MockedFunction<
  typeof api.fetchOrganizationTree
>;

function renderPage(page: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  }
  return { queryClient, ...render(<>{page}</>, { wrapper: Wrapper }) };
}

beforeEach(() => {
  jest.resetAllMocks();
});

describe("page-level query states", () => {
  it("renders the forbidden state for a 403 without retrying", async () => {
    fetchAnalyticsSummary.mockRejectedValue(new api.ApiError(403, "forbidden"));
    const { queryClient } = renderPage(<AnalyticsPage />);

    expect(await screen.findByTestId("forbidden-state")).toBeInTheDocument();
    expect(fetchAnalyticsSummary).toHaveBeenCalledTimes(1);
    expect(queryClient.getQueryState(queryKeys.analytics.summary())?.status).toBe("error");
  });

  it("renders the error state for a server failure", async () => {
    fetchAnalyticsSummary.mockRejectedValue(new api.ApiError(500, "boom"));
    renderPage(<AnalyticsPage />);
    expect(await screen.findByTestId("error-state")).toHaveTextContent("Failed to load analytics.");
  });

  it("renders the analytics empty state when the window has no data", async () => {
    fetchAnalyticsSummary.mockResolvedValue([]);
    renderPage(<AnalyticsPage />);
    expect(
      await screen.findByText("No analytics recorded in the selected window."),
    ).toBeInTheDocument();
  });

  it("renders the empty task list state", async () => {
    listTasks.mockResolvedValue([]);
    renderPage(<TasksPage />);
    expect(await screen.findByTestId("empty-state")).toHaveTextContent("No tasks yet.");
  });

  it("renders the empty organization state", async () => {
    fetchOrganizationTree.mockResolvedValue([]);
    renderPage(<OrganizationsPage />);
    expect(await screen.findByTestId("empty-state")).toHaveTextContent("No companies yet.");
  });
});
