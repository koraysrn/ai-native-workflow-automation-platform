import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";

import FormFileField from "../FormFileField";

function pdfFile(): File {
  return new File(["data"], "contract.pdf", { type: "application/pdf" });
}

describe("FormFileField", () => {
  it("uploads, shows progress and polls until the file is promoted", async () => {
    const onUploaded = jest.fn();
    let resolveUpload: ((fileId: string) => void) | undefined;
    const uploadFile = jest.fn(
      (_file: File, onProgress: (percent: number) => void) =>
        new Promise<string>((resolve) => {
          onProgress(50);
          resolveUpload = resolve;
        }),
    );
    const pollStatus = jest
      .fn()
      .mockResolvedValueOnce("SCANNING")
      .mockResolvedValueOnce("PROMOTED");

    render(
      <FormFileField
        fieldId="contract"
        onUploaded={onUploaded}
        onCleared={jest.fn()}
        uploadFile={uploadFile}
        pollStatus={pollStatus}
        pollIntervalMs={10}
      />,
    );

    fireEvent.change(screen.getByLabelText("contract file"), {
      target: { files: [pdfFile()] },
    });
    expect(screen.getByLabelText("contract upload progress")).toBeInTheDocument();

    await act(async () => {
      resolveUpload?.("f1");
    });

    expect(onUploaded).toHaveBeenCalledWith("f1");
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("Scan status: Scanning"),
    );
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("ready to submit"),
    );
    expect(pollStatus).toHaveBeenCalledWith("f1");
  });

  it("shows an error when the upload fails", async () => {
    const uploadFile = jest.fn().mockRejectedValue(new Error("offline"));

    render(
      <FormFileField
        fieldId="contract"
        onUploaded={jest.fn()}
        onCleared={jest.fn()}
        uploadFile={uploadFile}
      />,
    );

    fireEvent.change(screen.getByLabelText("contract file"), {
      target: { files: [pdfFile()] },
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("Upload failed");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("clears the reference when the file is removed", async () => {
    const onCleared = jest.fn();
    const uploadFile = jest.fn().mockResolvedValue("f1");

    render(
      <FormFileField
        fieldId="contract"
        onUploaded={jest.fn()}
        onCleared={onCleared}
        uploadFile={uploadFile}
        pollStatus={jest.fn().mockResolvedValue("PROMOTED")}
        pollIntervalMs={1000}
      />,
    );

    fireEvent.change(screen.getByLabelText("contract file"), {
      target: { files: [pdfFile()] },
    });
    await waitFor(() => expect(screen.getByRole("status")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "Remove file" }));

    expect(onCleared).toHaveBeenCalled();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});
