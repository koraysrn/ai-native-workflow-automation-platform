import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import * as api from "../../api";
import { queryKeys } from "../../queryKeys";
import {
  useArchiveDocument,
  useDecideTask,
  useDocuments,
  useFormDefinition,
  useStartWorkflow,
  useTasks,
  useWorkflowInstances,
} from "../resources";

jest.mock("../../api", () => {
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
    listTasks: jest.fn(),
    approveTask: jest.fn(),
    rejectTask: jest.fn(),
    listWorkflowDefinitions: jest.fn(),
    listWorkflowInstances: jest.fn(),
    startWorkflowInstance: jest.fn(),
    createWorkflowDefinition: jest.fn(),
    validateWorkflowExpression: jest.fn(),
    listDocuments: jest.fn(),
    createDocumentUpload: jest.fn(),
    archiveDocumentVersion: jest.fn(),
    getDocumentDownloadUrl: jest.fn(),
    fetchFormDefinitions: jest.fn(),
    fetchFormDefinition: jest.fn(),
    submitForm: jest.fn(),
    createFormDefinition: jest.fn(),
    fetchAnalyticsSummary: jest.fn(),
    fetchOrganizationTree: jest.fn(),
    fetchTenants: jest.fn(),
    healthCheck: jest.fn(),
    registerTenant: jest.fn(),
  };
});

const listTasks = api.listTasks as jest.MockedFunction<typeof api.listTasks>;
const approveTask = api.approveTask as jest.MockedFunction<typeof api.approveTask>;
const listWorkflowInstances = api.listWorkflowInstances as jest.MockedFunction<
  typeof api.listWorkflowInstances
>;
const startWorkflowInstance = api.startWorkflowInstance as jest.MockedFunction<
  typeof api.startWorkflowInstance
>;
const listDocuments = api.listDocuments as jest.MockedFunction<typeof api.listDocuments>;
const archiveDocumentVersion = api.archiveDocumentVersion as jest.MockedFunction<
  typeof api.archiveDocumentVersion
>;
const fetchFormDefinition = api.fetchFormDefinition as jest.MockedFunction<
  typeof api.fetchFormDefinition
>;

function wrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

/** Same policy as production, minus retries so failures surface immediately. */
function newQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 30_000 },
      mutations: { retry: false },
    },
  });
}

beforeEach(() => {
  jest.resetAllMocks();
});

describe("resource hooks", () => {
  it("serves two consumers from one request using the shared key", async () => {
    listTasks.mockResolvedValue([{ id: "t1" } as api.TaskDto]);
    const queryClient = newQueryClient();
    const first = renderHook(() => useTasks(), { wrapper: wrapper(queryClient) });
    const second = renderHook(() => useTasks(), { wrapper: wrapper(queryClient) });

    await waitFor(() => expect(first.result.current.isSuccess).toBe(true));
    await waitFor(() => expect(second.result.current.isSuccess).toBe(true));

    expect(listTasks).toHaveBeenCalledTimes(1);
    expect(second.result.current.data).toEqual([{ id: "t1" }]);
    expect(queryClient.getQueryData(queryKeys.tasks.list())).toEqual([{ id: "t1" }]);
  });

  it("writes an approval into the cache and revalidates the list", async () => {
    const open = { id: "t1", title: "Approve me", status: "OPEN" } as api.TaskDto;
    const approved = { ...open, status: "APPROVED" } as api.TaskDto;
    // The invalidation refetches; the server already returns the approved row.
    listTasks.mockResolvedValueOnce([open]).mockResolvedValue([approved]);
    approveTask.mockResolvedValue(approved);
    const queryClient = newQueryClient();

    const { result } = renderHook(() => ({ tasks: useTasks(), decide: useDecideTask() }), {
      wrapper: wrapper(queryClient),
    });
    await waitFor(() => expect(result.current.tasks.isSuccess).toBe(true));

    await act(async () => {
      await result.current.decide.mutateAsync({ id: "t1", approve: true, comment: "ok" });
    });

    await waitFor(() => expect(listTasks).toHaveBeenCalledTimes(2));
    expect(approveTask).toHaveBeenCalledWith("t1", "ok");
    expect(queryClient.getQueryData(queryKeys.tasks.list())).toEqual([approved]);
  });

  it("leaves the cache untouched when a mutation fails", async () => {
    const open = { id: "t1", title: "Approve me", status: "OPEN" } as api.TaskDto;
    listTasks.mockResolvedValue([open]);
    approveTask.mockRejectedValue(new Error("conflict"));
    const queryClient = newQueryClient();

    const { result } = renderHook(() => ({ tasks: useTasks(), decide: useDecideTask() }), {
      wrapper: wrapper(queryClient),
    });
    await waitFor(() => expect(result.current.tasks.isSuccess).toBe(true));

    await act(async () => {
      await expect(
        result.current.decide.mutateAsync({ id: "t1", approve: true, comment: "" }),
      ).rejects.toThrow("conflict");
    });

    expect(queryClient.getQueryData(queryKeys.tasks.list())).toEqual([open]);
    expect(listTasks).toHaveBeenCalledTimes(1);
  });

  it("reflows a rejection through the same mutation with a conflict status", async () => {
    const open = { id: "t1", title: "Approve me", status: "OPEN" } as api.TaskDto;
    listTasks.mockResolvedValue([open]);
    (api.rejectTask as jest.Mock).mockResolvedValue({ ...open, status: "REJECTED" });
    const queryClient = newQueryClient();
    const { result } = renderHook(() => useDecideTask(), { wrapper: wrapper(queryClient) });

    await act(async () => {
      await result.current.mutateAsync({ id: "t1", approve: false, comment: "no" });
    });
    expect(api.rejectTask).toHaveBeenCalledWith("t1", "no");
    expect(approveTask).not.toHaveBeenCalled();
  });

  it("invalidates instances after starting a workflow", async () => {
    listWorkflowInstances.mockResolvedValue([]);
    startWorkflowInstance.mockResolvedValue({ id: "i1" } as api.WorkflowInstanceDto);
    const queryClient = newQueryClient();
    const { result } = renderHook(
      () => ({ instances: useWorkflowInstances(), start: useStartWorkflow() }),
      { wrapper: wrapper(queryClient) },
    );
    await waitFor(() => expect(result.current.instances.isSuccess).toBe(true));

    await act(async () => {
      await result.current.start.mutateAsync({
        workflowDefinitionId: "wf-1",
        contextJson: '{"a":1}',
      });
    });
    await waitFor(() => expect(listWorkflowInstances).toHaveBeenCalledTimes(2));
    expect(startWorkflowInstance).toHaveBeenCalledWith("wf-1", '{"a":1}');
  });

  it("invalidates documents after archiving a version", async () => {
    listDocuments.mockResolvedValue([]);
    archiveDocumentVersion.mockResolvedValue({ id: "v1" } as api.DocumentVersionDto);
    const queryClient = newQueryClient();
    const { result } = renderHook(
      () => ({ documents: useDocuments(), archive: useArchiveDocument() }),
      { wrapper: wrapper(queryClient) },
    );
    await waitFor(() => expect(result.current.documents.isSuccess).toBe(true));

    await act(async () => {
      await result.current.archive.mutateAsync("v1");
    });
    await waitFor(() => expect(listDocuments).toHaveBeenCalledTimes(2));
  });

  it("does not request a form until an id is selected", async () => {
    fetchFormDefinition.mockResolvedValue({ id: "f1" } as api.FormDefinitionDetailDto);
    const queryClient = newQueryClient();
    const { result, rerender } = renderHook(
      ({ formId }: { formId: string }) => useFormDefinition(formId),
      { wrapper: wrapper(queryClient), initialProps: { formId: "" } },
    );

    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchFormDefinition).not.toHaveBeenCalled();

    rerender({ formId: "f1" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(fetchFormDefinition).toHaveBeenCalledWith("f1");
  });
});
