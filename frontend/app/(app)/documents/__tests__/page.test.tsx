import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import * as api from "../../../../lib/api";
import {
  FakeEventSource,
  documentDto,
  installFakeEventSource,
  removeEventSource,
  scanEvent,
} from "../../../../test-utils/fakeEventSource";
import DocumentsPage from "../page";

jest.mock("../../../../lib/api", () => ({
  listDocuments: jest.fn(),
  createDocumentUpload: jest.fn(),
  getDocumentDownloadUrl: jest.fn(),
  archiveDocumentVersion: jest.fn(),
  documentScanEventsUrl: jest.fn(() => "http://api.test/api/v1/documents/events"),
}));

const listDocuments = api.listDocuments as jest.MockedFunction<typeof api.listDocuments>;

let restore: (() => void) | undefined;

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  }
  return render(<DocumentsPage />, { wrapper: Wrapper });
}

beforeEach(() => {
  jest.resetAllMocks();
  listDocuments.mockResolvedValue([documentDto()]);
});

afterEach(() => {
  restore?.();
});

describe("DocumentsPage live scan status", () => {
  it("updates the row in place and enables download only after PROMOTED", async () => {
    restore = installFakeEventSource();
    renderPage();

    expect(await screen.findByTestId("scan-status-file-1")).toHaveTextContent(
      "Quarantined",
    );
    const download = screen.getByRole("button", { name: "Download" });
    expect(download).toBeDisabled();

    act(() =>
      FakeEventSource.instances[0]!.emit(
        scanEvent({ status: "SCANNING", detail: "Antivirus scan started." }),
      ),
    );
    await waitFor(() =>
      expect(screen.getByTestId("scan-status-file-1")).toHaveTextContent("Scanning"),
    );
    expect(screen.getByTestId("document-scan-announcer")).toHaveTextContent(
      "report.pdf: scan status changed to Scanning.",
    );
    expect(download).toBeDisabled();

    act(() => FakeEventSource.instances[0]!.emit(scanEvent({ status: "PROMOTED" })));
    await waitFor(() =>
      expect(screen.getByTestId("scan-status-file-1")).toHaveTextContent(
        "Ready to download",
      ),
    );
    expect(screen.getByRole("button", { name: "Download" })).toBeEnabled();
  });

  it("shows an alert on INFECTED, announces it and keeps download disabled", async () => {
    restore = installFakeEventSource();
    listDocuments.mockResolvedValue([
      documentDto({ name: "eicar.pdf", scanStatus: "SCANNING" }),
    ]);
    renderPage();
    await screen.findByText("eicar.pdf");

    act(() =>
      FakeEventSource.instances[0]!.emit(
        scanEvent({
          name: "eicar.pdf",
          status: "INFECTED",
          detail: "Antivirus scanning rejected the file.",
        }),
      ),
    );

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Infected file blocked.");
    expect(alert).toHaveTextContent(
      "eicar.pdf was rejected by antivirus scanning and cannot be downloaded.",
    );
    expect(screen.getByTestId("document-scan-announcer")).toHaveTextContent(
      "eicar.pdf: scan status changed to Infected.",
    );
    expect(screen.getByRole("button", { name: "Download" })).toBeDisabled();
  });

  it("renders hostile filenames as text and never as markup", async () => {
    restore = installFakeEventSource();
    listDocuments.mockResolvedValue([
      documentDto({ name: '<img src=x onerror="alert(1)">.pdf' }),
    ]);
    const { container } = renderPage();

    expect(
      await screen.findByText('<img src=x onerror="alert(1)">.pdf'),
    ).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });

  it("polls and says so when EventSource is unavailable", async () => {
    restore = removeEventSource();
    renderPage();

    expect(
      await screen.findByText(
        "Live updates are unavailable; refreshing status by polling.",
      ),
    ).toBeInTheDocument();
    await waitFor(() => expect(listDocuments).toHaveBeenCalled());
  });
});
