import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import * as api from "../../api";
import {
  FakeEventSource,
  documentDto,
  installFakeEventSource,
  removeEventSource,
  scanEvent,
} from "../../../test-utils/fakeEventSource";
import { DOCUMENTS_QUERY_KEY, useDocumentScanStatus } from "../useDocumentScanStatus";

jest.mock("../../api", () => ({
  documentScanEventsUrl: jest.fn(() => "http://api.test/api/v1/documents/events"),
  listDocuments: jest.fn(),
}));

const listDocuments = api.listDocuments as jest.MockedFunction<typeof api.listDocuments>;

function wrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

function newQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

let restore: (() => void) | undefined;

afterEach(() => {
  restore?.();
  jest.useRealTimers();
  jest.resetAllMocks();
});

describe("useDocumentScanStatus", () => {
  it("applies SSE events to the document cache and exposes the last event", async () => {
    restore = installFakeEventSource();
    const queryClient = newQueryClient();
    queryClient.setQueryData(DOCUMENTS_QUERY_KEY, [documentDto()]);

    const { result } = renderHook(() => useDocumentScanStatus(), {
      wrapper: wrapper(queryClient),
    });

    expect(FakeEventSource.instances).toHaveLength(1);
    act(() => FakeEventSource.instances[0]!.open());
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() =>
      FakeEventSource.instances[0]!.emit(
        scanEvent({ status: "SCANNING", detail: "Antivirus scan started." }),
      ),
    );

    await waitFor(() =>
      expect(
        queryClient.getQueryData<ReturnType<typeof documentDto>[]>(DOCUMENTS_QUERY_KEY)?.[0]
          ?.scanStatus,
      ).toBe("SCANNING"),
    );
    expect(result.current.lastEvent?.status).toBe("SCANNING");
    expect(result.current.transport).toBe("sse");
  });

  it("reconnects with backoff after a network drop and resumes updates", async () => {
    jest.useFakeTimers();
    restore = installFakeEventSource();
    const queryClient = newQueryClient();
    queryClient.setQueryData(DOCUMENTS_QUERY_KEY, [documentDto()]);

    const { result } = renderHook(() => useDocumentScanStatus(), {
      wrapper: wrapper(queryClient),
    });
    act(() => FakeEventSource.instances[0]!.fail());

    expect(result.current.connected).toBe(false);
    await act(async () => {
      jest.advanceTimersByTime(1000);
    });
    expect(FakeEventSource.instances).toHaveLength(2);

    act(() => FakeEventSource.instances[1]!.open());
    act(() =>
      FakeEventSource.instances[1]!.emit(scanEvent({ status: "PROMOTED" })),
    );

    expect(
      queryClient.getQueryData<ReturnType<typeof documentDto>[]>(DOCUMENTS_QUERY_KEY)?.[0]
        ?.scanStatus,
    ).toBe("PROMOTED");
  });

  it("falls back to polling when EventSource is unsupported", async () => {
    restore = removeEventSource();
    listDocuments.mockResolvedValue([documentDto({ scanStatus: "PROMOTED" })]);
    const queryClient = newQueryClient();

    const { result } = renderHook(() => useDocumentScanStatus(), {
      wrapper: wrapper(queryClient),
    });

    await waitFor(() => expect(result.current.transport).toBe("polling"));
    await waitFor(() =>
      expect(
        queryClient.getQueryData<ReturnType<typeof documentDto>[]>(DOCUMENTS_QUERY_KEY)?.[0]
          ?.scanStatus,
      ).toBe("PROMOTED"),
    );
    expect(listDocuments).toHaveBeenCalled();
  });

  it("removes a deleted document from the cache without closing the tenant stream", async () => {
    restore = installFakeEventSource();
    const queryClient = newQueryClient();
    queryClient.setQueryData(DOCUMENTS_QUERY_KEY, [
      documentDto(),
      documentDto({ id: "version-2", fileId: "file-2", name: "other.pdf" }),
    ]);
    renderHook(() => useDocumentScanStatus(), {
      wrapper: wrapper(queryClient),
    });
    act(() => FakeEventSource.instances[0]!.open());

    act(() =>
      FakeEventSource.instances[0]!.emitNamed("document-deleted", { fileId: "file-1" }),
    );

    await waitFor(() =>
      expect(
        queryClient.getQueryData<ReturnType<typeof documentDto>[]>(DOCUMENTS_QUERY_KEY),
      ).toHaveLength(1),
    );
    expect(
      queryClient.getQueryData<ReturnType<typeof documentDto>[]>(DOCUMENTS_QUERY_KEY)?.[0]
        ?.fileId,
    ).toBe("file-2");
    expect(FakeEventSource.instances[0]!.closed).toBe(false);
  });

  it("ignores malformed events without tearing down the stream", async () => {
    restore = installFakeEventSource();
    const queryClient = newQueryClient();
    queryClient.setQueryData(DOCUMENTS_QUERY_KEY, [documentDto()]);
    const { result } = renderHook(() => useDocumentScanStatus(), {
      wrapper: wrapper(queryClient),
    });
    act(() => FakeEventSource.instances[0]!.open());

    act(() => FakeEventSource.instances[0]!.emit("{not-json"));
    act(() =>
      FakeEventSource.instances[0]!.emit({ fileId: "file-1", name: "missing status" }),
    );
    act(() => FakeEventSource.instances[0]!.emitNamed("document-deleted", "{bad-json"));
    act(() => FakeEventSource.instances[0]!.emitNamed("document-deleted", {}));

    expect(result.current.lastEvent).toBeNull();
    expect(FakeEventSource.instances[0]!.closed).toBe(false);
  });

  it("does not open a stream when disabled", () => {
    restore = installFakeEventSource();
    const queryClient = newQueryClient();

    const { result } = renderHook(() => useDocumentScanStatus({ enabled: false }), {
      wrapper: wrapper(queryClient),
    });

    expect(FakeEventSource.instances).toHaveLength(0);
    expect(result.current.connected).toBe(false);
    expect(result.current.transport).toBe("sse");
  });

  it("falls back to polling when EventSource construction throws", async () => {
    restore = installFakeEventSource();
    const blocked = class {
      constructor() {
        throw new Error("EventSource blocked");
      }
    };
    (window as unknown as { EventSource: unknown }).EventSource = blocked;
    listDocuments.mockResolvedValue([documentDto({ scanStatus: "PROMOTED" })]);
    const queryClient = newQueryClient();

    const { result } = renderHook(() => useDocumentScanStatus(), {
      wrapper: wrapper(queryClient),
    });

    await waitFor(() => expect(result.current.transport).toBe("polling"));
    await waitFor(() => expect(listDocuments).toHaveBeenCalled());
  });

  it("falls back to polling after the reconnect budget is exhausted", async () => {
    jest.useFakeTimers();
    restore = installFakeEventSource();
    listDocuments.mockResolvedValue([documentDto()]);
    const queryClient = newQueryClient();
    const { result } = renderHook(
      () => useDocumentScanStatus({ maxReconnectAttempts: 1 }),
      { wrapper: wrapper(queryClient) },
    );

    act(() => FakeEventSource.instances[0]!.fail());
    await act(async () => {
      jest.advanceTimersByTime(1000);
    });
    expect(FakeEventSource.instances).toHaveLength(2);

    act(() => FakeEventSource.instances[1]!.fail());
    await act(async () => {
      await Promise.resolve();
    });

    expect(result.current.transport).toBe("polling");
    expect(listDocuments).toHaveBeenCalled();
  });

  it("survives a rejected polling request", async () => {
    restore = removeEventSource();
    listDocuments.mockRejectedValue(new Error("offline"));
    const queryClient = newQueryClient();

    const { result } = renderHook(() => useDocumentScanStatus(), {
      wrapper: wrapper(queryClient),
    });

    await waitFor(() => expect(result.current.transport).toBe("polling"));
    expect(listDocuments).toHaveBeenCalled();
  });

  it("closes the stream while hidden and reconnects when visible", async () => {
    restore = installFakeEventSource();
    let visibility: DocumentVisibilityState = "visible";
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      get: () => visibility,
    });
    const queryClient = newQueryClient();
    const { result } = renderHook(() => useDocumentScanStatus(), {
      wrapper: wrapper(queryClient),
    });
    act(() => FakeEventSource.instances[0]!.open());
    await waitFor(() => expect(result.current.connected).toBe(true));

    // A visible event with an already-open source takes the early-return path.
    act(() => document.dispatchEvent(new Event("visibilitychange")));
    expect(FakeEventSource.instances).toHaveLength(1);

    visibility = "hidden";
    act(() => document.dispatchEvent(new Event("visibilitychange")));
    expect(FakeEventSource.instances[0]!.closed).toBe(true);
    expect(result.current.connected).toBe(false);

    visibility = "visible";
    act(() => document.dispatchEvent(new Event("visibilitychange")));
    expect(FakeEventSource.instances).toHaveLength(2);

    delete (document as unknown as { visibilityState?: DocumentVisibilityState })
      .visibilityState;
  });

  it("cancels a pending reconnect and ignores late events after unmount", async () => {
    jest.useFakeTimers();
    restore = installFakeEventSource();
    const queryClient = newQueryClient();
    queryClient.setQueryData(DOCUMENTS_QUERY_KEY, [documentDto()]);
    const { result, unmount } = renderHook(() => useDocumentScanStatus(), {
      wrapper: wrapper(queryClient),
    });
    const first = FakeEventSource.instances[0]!;
    act(() => first.fail());
    expect(FakeEventSource.instances).toHaveLength(1);

    unmount();
    await act(async () => {
      jest.advanceTimersByTime(30_000);
    });
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(result.current.lastEvent).toBeNull();

    act(() => {
      first.emit(scanEvent({ status: "PROMOTED" }));
      first.fail();
    });
    expect(result.current.lastEvent).toBeNull();
  });
});
