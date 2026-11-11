import type { DocumentVersionDto } from "../api";
import {
  findScanStatus,
  isScanReady,
  isTerminalScan,
  putFile,
  uploadFormFile,
  uploadPercent,
} from "../formFileUpload";

function document(fileId: string, scanStatus: string | null): DocumentVersionDto {
  return {
    id: `version-${fileId}`,
    fileId,
    version: 1,
    size: 10,
    format: "pdf",
    status: "ACTIVE",
    createdAt: "2026-11-11T00:00:00Z",
    name: "doc.pdf",
    scanStatus,
    scanError: null,
    downloadable: scanStatus === "PROMOTED",
  };
}

describe("uploadPercent", () => {
  it("clamps and rounds the transfer progress", () => {
    expect(uploadPercent(50, 100)).toBe(50);
    expect(uploadPercent(1, 3)).toBe(33);
    expect(uploadPercent(-5, 100)).toBe(0);
    expect(uploadPercent(200, 100)).toBe(100);
    expect(uploadPercent(10, 0)).toBe(0);
    expect(uploadPercent(10, Number.POSITIVE_INFINITY)).toBe(0);
  });
});

describe("scan helpers", () => {
  it("only treats PROMOTED as ready", () => {
    expect(isScanReady("PROMOTED")).toBe(true);
    expect(isScanReady("CLEAN")).toBe(false);
    expect(isScanReady(null)).toBe(false);
  });

  it("detects terminal scans", () => {
    expect(isTerminalScan("PROMOTED")).toBe(true);
    expect(isTerminalScan("INFECTED")).toBe(true);
    expect(isTerminalScan("SCAN_FAILED")).toBe(true);
    expect(isTerminalScan("SCANNING")).toBe(false);
  });

  it("finds the live status of one file", () => {
    const documents = [document("a", "SCANNING"), document("b", "PROMOTED")];
    expect(findScanStatus(documents, "b")).toBe("PROMOTED");
    expect(findScanStatus(documents, "missing")).toBeNull();
  });
});

describe("putFile", () => {
  class FakeXhr {
    upload: { onprogress: ((event: ProgressEvent) => void) | null } = { onprogress: null };
    status = 0;
    onload: (() => void) | null = null;
    onerror: (() => void) | null = null;
    opened: { method: string; url: string } | null = null;
    sent: Blob | null = null;

    open(method: string, url: string) {
      this.opened = { method, url };
    }

    send(body: Blob) {
      this.sent = body;
    }

    progress(loaded: number, total: number) {
      this.upload.onprogress?.({
        lengthComputable: true,
        loaded,
        total,
      } as ProgressEvent);
    }
  }

  it("reports progress and resolves on a 2xx response", async () => {
    const xhr = new FakeXhr();
    const percents: number[] = [];
    const promise = putFile("https://upload", new Blob(["x"]), (percent) => percents.push(percent), () => xhr as unknown as XMLHttpRequest);

    expect(xhr.opened).toEqual({ method: "PUT", url: "https://upload" });
    xhr.progress(25, 100);
    xhr.status = 200;
    xhr.onload?.();
    await promise;

    expect(percents).toEqual([25]);
    expect(xhr.sent).not.toBeNull();
  });

  it("rejects a non-2xx response", async () => {
    const xhr = new FakeXhr();
    const promise = putFile("https://upload", new Blob(["x"]), () => undefined, () => xhr as unknown as XMLHttpRequest);

    xhr.status = 500;
    xhr.onload?.();
    await expect(promise).rejects.toThrow("status 500");
  });

  it("rejects a network error", async () => {
    const xhr = new FakeXhr();
    const promise = putFile("https://upload", new Blob(["x"]), () => undefined, () => xhr as unknown as XMLHttpRequest);

    xhr.onerror?.();
    await expect(promise).rejects.toThrow("Upload failed.");
  });
});

describe("uploadFormFile", () => {
  it("creates the slot, uploads the bytes and returns the file id", async () => {
    const progress: number[] = [];
    const file = new File(["data"], "contract.pdf", { type: "application/pdf" });
    const createDocumentUpload = jest.fn().mockResolvedValue({
      fileId: "f1",
      uploadUrl: "https://upload",
      version: document("f1", "QUARANTINED"),
    });
    const put = jest.fn(async (_url: string, _file: Blob, onProgress: (p: number) => void) => {
      onProgress(100);
    });

    const fileId = await uploadFormFile(file, (percent) => progress.push(percent), {
      createDocumentUpload,
      putFile: put,
    });

    expect(fileId).toBe("f1");
    expect(createDocumentUpload).toHaveBeenCalledWith("contract.pdf", "application/pdf", 4);
    expect(put).toHaveBeenCalledWith("https://upload", file, expect.any(Function));
    expect(progress).toEqual([100]);
  });

  it("falls back to a binary content type when the browser reports none", async () => {
    const file = new File(["data"], "contract.bin", { type: "" });
    const createDocumentUpload = jest.fn().mockResolvedValue({
      fileId: "f2",
      uploadUrl: "https://upload",
      version: document("f2", "QUARANTINED"),
    });

    await uploadFormFile(file, () => undefined, {
      createDocumentUpload,
      putFile: jest.fn(),
    });

    expect(createDocumentUpload).toHaveBeenCalledWith(
      "contract.bin",
      "application/octet-stream",
      4,
    );
  });
});
