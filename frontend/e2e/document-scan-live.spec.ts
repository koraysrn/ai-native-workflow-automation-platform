import { expect, test, type Page } from "@playwright/test";

/**
 * Live document scan status (#135). EventSource is stubbed in the browser because Playwright route
 * fulfilment cannot hold a streaming response open; everything else (list fetch, React Query cache,
 * row rendering and download gating) runs through the real page.
 */
const API = "**/api/v1/**";

async function openDocuments(page: Page, scanStatus = "QUARANTINED"): Promise<void> {
  await page.context().addCookies([
    { name: "awap_session", value: "1", url: "http://localhost:3000" },
    { name: "awap_tenant", value: "tenant-a", url: "http://localhost:3000" },
  ]);
  await page.addInitScript(() => {
    const scope = window as unknown as {
      EventSource?: unknown;
      __emitDocumentScan?: (payload: unknown) => void;
    };
    class FakeEventSource {
      static instances: FakeEventSource[] = [];
      onopen: ((event: Event) => void) | null = null;
      onerror: ((event: Event) => void) | null = null;
      closed = false;
      private readonly listeners = new Map<string, Array<(event: { data: string }) => void>>();

      constructor(
        readonly url: string,
        readonly options?: EventSourceInit,
      ) {
        FakeEventSource.instances.push(this);
      }

      addEventListener(type: string, listener: (event: { data: string }) => void): void {
        const list = this.listeners.get(type) ?? [];
        list.push(listener);
        this.listeners.set(type, list);
      }

      close(): void {
        this.closed = true;
      }

      emit(payload: unknown): void {
        const event = { data: JSON.stringify(payload) };
        (this.listeners.get("scan-status") ?? []).forEach((listener) => listener(event));
      }
    }
    scope.EventSource = FakeEventSource;
    scope.__emitDocumentScan = (payload: unknown) => {
      const source = FakeEventSource.instances[0];
      if (!source) {
        return;
      }
      source.onopen?.(new Event("open"));
      source.emit(payload);
    };
  });
  await page.route(`${API}documents`, async (route) => {
    if (route.request().method() !== "GET") {
      await route.fallback();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          id: "version-1",
          fileId: "file-1",
          version: 1,
          size: 2048,
          format: "pdf",
          status: "PENDING",
          createdAt: "2026-01-01T00:00:00Z",
          name: "report.pdf",
          scanStatus,
          scanError: null,
          downloadable: scanStatus === "PROMOTED",
        },
      ]),
    });
  });
  await page.goto("/documents");
  await expect(page.getByRole("heading", { name: "Documents" })).toBeVisible();
}

function event(status: string, detail = "") {
  return {
    tenantId: "tenant-a",
    fileId: "file-1",
    name: "report.pdf",
    status,
    detail,
    occurredAt: "2026-01-01T00:00:01Z",
  };
}

test("updates a row through every scan status and gates download on PROMOTED", async ({ page }) => {
  await openDocuments(page);
  await expect(page.getByTestId("scan-status-file-1")).toHaveText("Quarantined");
  await expect(page.getByRole("button", { name: "Download" })).toBeDisabled();

  for (const status of ["SCANNING", "CLEAN"]) {
    await page.evaluate((payload) => {
      (window as unknown as { __emitDocumentScan: (value: unknown) => void }).__emitDocumentScan(
        payload,
      );
    }, event(status));
  }
  await expect(page.getByTestId("scan-status-file-1")).toHaveText("Clean");
  await expect(page.getByRole("button", { name: "Download" })).toBeDisabled();

  await page.evaluate((payload) => {
    (window as unknown as { __emitDocumentScan: (value: unknown) => void }).__emitDocumentScan(
      payload,
    );
  }, event("PROMOTED"));
  await expect(page.getByTestId("scan-status-file-1")).toHaveText("Ready to download");
  await expect(page.getByRole("button", { name: "Download" })).toBeEnabled();
});

test("announces an INFECTED file and keeps download disabled", async ({ page }) => {
  await openDocuments(page, "SCANNING");
  await page.evaluate((payload) => {
    (window as unknown as { __emitDocumentScan: (value: unknown) => void }).__emitDocumentScan(
      payload,
    );
  }, event("INFECTED", "Antivirus scanning rejected the file."));

  await expect(page.getByRole("alert")).toContainText("Infected file blocked.");
  await expect(page.getByTestId("document-scan-announcer")).toContainText(
    "report.pdf: scan status changed to Infected.",
  );
  await expect(page.getByRole("button", { name: "Download" })).toBeDisabled();
});
