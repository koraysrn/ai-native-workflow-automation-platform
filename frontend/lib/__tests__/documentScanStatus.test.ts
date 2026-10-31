import {
  applyScanStatusEvent,
  infectionMessage,
  isDocumentDownloadable,
  removeDocumentByFileId,
  scanStatusAnnouncement,
  scanStatusLabel,
} from "../documentScanStatus";
import { documentDto, scanEvent } from "../../test-utils/fakeEventSource";

describe("document scan status helpers", () => {
  it("applies a status update in place and enables download only on PROMOTED", () => {
    const documents = [documentDto()];

    const scanning = applyScanStatusEvent(
      documents,
      scanEvent({ status: "SCANNING", detail: "Antivirus scan started." }),
    );
    expect(scanning?.[0]).toMatchObject({
      scanStatus: "SCANNING",
      scanError: "Antivirus scan started.",
      downloadable: false,
    });

    const promoted = applyScanStatusEvent(
      scanning,
      scanEvent({ status: "PROMOTED", detail: "File promoted and available for download." }),
    );
    expect(promoted?.[0]?.scanStatus).toBe("PROMOTED");
    expect(isDocumentDownloadable(promoted![0]!)).toBe(true);
  });

  it("is idempotent for duplicate events and ignores unknown file ids", () => {
    const documents = [documentDto({ scanStatus: "SCANNING", downloadable: false })];
    const once = applyScanStatusEvent(
      documents,
      scanEvent({ status: "INFECTED", detail: "malware" }),
    );
    const twice = applyScanStatusEvent(
      once,
      scanEvent({ status: "INFECTED", detail: "malware" }),
    );

    expect(twice?.[0]).toMatchObject({ scanStatus: "INFECTED", scanError: "malware" });
    expect(applyScanStatusEvent(documents, scanEvent({ fileId: "other" }))).toBe(documents);
    expect(applyScanStatusEvent(undefined, scanEvent())).toBeUndefined();
  });

  it("removes only the deleted file and keeps the array when it is absent", () => {
    const documents = [documentDto(), documentDto({ id: "version-2", fileId: "file-2" })];
    const remaining = removeDocumentByFileId(documents, "file-1");

    expect(remaining).toHaveLength(1);
    expect(remaining?.[0]?.fileId).toBe("file-2");
    expect(removeDocumentByFileId(documents, "missing")).toBe(documents);
    expect(removeDocumentByFileId(undefined, "file-1")).toBeUndefined();
  });

  it("keeps legacy rows non-downloadable when scanStatus is missing", () => {
    expect(
      isDocumentDownloadable({ scanStatus: null, downloadable: true }),
    ).toBe(false);
    expect(scanStatusLabel(null)).toBe("Unknown");
    expect(scanStatusLabel("SCAN_FAILED")).toBe("Scan failed");
  });

  it("builds accessible announcements and a clear infection message", () => {
    const infected = scanEvent({ status: "INFECTED", name: "eicar.pdf" });
    expect(scanStatusAnnouncement(infected)).toBe(
      "eicar.pdf: scan status changed to Infected.",
    );
    expect(infectionMessage(infected)).toBe(
      "eicar.pdf was rejected by antivirus scanning and cannot be downloaded.",
    );
  });
});
