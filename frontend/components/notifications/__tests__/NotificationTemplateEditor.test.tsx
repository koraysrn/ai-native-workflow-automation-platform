import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import * as api from "../../../lib/api";
import NotificationTemplateEditor from "../NotificationTemplateEditor";

jest.mock("../../../lib/api", () => ({
  listNotificationTemplates: jest.fn(),
  createNotificationTemplate: jest.fn(),
  updateNotificationTemplate: jest.fn(),
  archiveNotificationTemplate: jest.fn(),
  previewNotificationTemplate: jest.fn(),
}));

const createNotificationTemplate = api.createNotificationTemplate as jest.MockedFunction<
  typeof api.createNotificationTemplate
>;
const previewNotificationTemplate = api.previewNotificationTemplate as jest.MockedFunction<
  typeof api.previewNotificationTemplate
>;

function renderEditor() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <NotificationTemplateEditor />
    </QueryClientProvider>,
  );
}

function fillForm(fields: { key?: string; subject?: string; body?: string; variables?: string }) {
  if (fields.key !== undefined) {
    fireEvent.change(screen.getByLabelText("Template key"), { target: { value: fields.key } });
  }
  if (fields.subject !== undefined) {
    fireEvent.change(screen.getByLabelText("Subject"), { target: { value: fields.subject } });
  }
  if (fields.body !== undefined) {
    fireEvent.change(screen.getByLabelText("Body"), { target: { value: fields.body } });
  }
  if (fields.variables !== undefined) {
    fireEvent.change(screen.getByLabelText("Variables JSON"), {
      target: { value: fields.variables },
    });
  }
}

beforeEach(() => {
  jest.resetAllMocks();
});

describe("NotificationTemplateEditor", () => {
  it("rejects unbalanced placeholders before calling the API", async () => {
    renderEditor();
    fillForm({ key: "welcome", subject: "Welcome", body: "Hello {{name" });

    fireEvent.click(screen.getByRole("button", { name: "Preview template" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("unbalanced");
    expect(previewNotificationTemplate).not.toHaveBeenCalled();
  });

  it("rejects unknown placeholders with the placeholder name", async () => {
    renderEditor();
    fillForm({ key: "welcome", subject: "Welcome", body: "Hello {{name}}", variables: "{}" });

    fireEvent.click(screen.getByRole("button", { name: "Preview template" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Unknown placeholders: name.");
    expect(previewNotificationTemplate).not.toHaveBeenCalled();
  });

  it("rejects malformed variables JSON", async () => {
    renderEditor();
    fillForm({ key: "welcome", subject: "Welcome", body: "Hello", variables: "{oops" });

    fireEvent.click(screen.getByRole("button", { name: "Preview template" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("valid JSON");
  });

  it("renders the preview returned by the API", async () => {
    previewNotificationTemplate.mockResolvedValue({
      subject: "Welcome",
      body: "Hello Ada",
      placeholders: ["name"],
    });
    renderEditor();
    fillForm({
      key: "welcome",
      subject: "Welcome",
      body: "Hello {{name}}",
      variables: '{"name":"Ada"}',
    });

    fireEvent.click(screen.getByRole("button", { name: "Preview template" }));

    await waitFor(() =>
      expect(previewNotificationTemplate).toHaveBeenCalledWith({
        templateKey: "welcome",
        locale: "en",
        subject: "Welcome",
        body: "Hello {{name}}",
        variablesJson: '{"name":"Ada"}',
      }),
    );
    expect(await screen.findByTestId("preview-body")).toHaveTextContent("Hello Ada");
    expect(screen.getByText("Placeholders: name")).toBeInTheDocument();
  });

  it("saves the template through the create mutation", async () => {
    createNotificationTemplate.mockResolvedValue({
      id: "t1",
      templateKey: "welcome",
      locale: "en",
      subject: "Welcome",
      body: "Hello {{name}}",
      version: 0,
      active: true,
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    });
    renderEditor();
    fillForm({
      key: "welcome",
      subject: "Welcome",
      body: "Hello {{name}}",
      variables: '{"name":"Ada"}',
    });

    fireEvent.click(screen.getByRole("button", { name: "Save template" }));

    await waitFor(() =>
      expect(createNotificationTemplate).toHaveBeenCalledWith({
        templateKey: "welcome",
        locale: "en",
        subject: "Welcome",
        body: "Hello {{name}}",
      }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Template saved.");
  });

  it("warns before leaving with unsaved changes", () => {
    renderEditor();

    const untouched = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(untouched);
    expect(untouched.defaultPrevented).toBe(false);

    fillForm({ key: "welcome" });
    const dirty = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);
  });
});
