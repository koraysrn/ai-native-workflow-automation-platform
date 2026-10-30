import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import * as api from "../../../lib/api";
import NotificationTemplateEditor from "../NotificationTemplateEditor";

jest.mock("../../../lib/api", () => ({
  listNotificationTemplates: jest.fn(),
  createNotificationTemplate: jest.fn(),
  updateNotificationTemplate: jest.fn(),
  archiveNotificationTemplate: jest.fn(),
  activateNotificationTemplate: jest.fn(),
  previewNotificationTemplate: jest.fn(),
}));

const listNotificationTemplates = api.listNotificationTemplates as jest.MockedFunction<
  typeof api.listNotificationTemplates
>;
const createNotificationTemplate = api.createNotificationTemplate as jest.MockedFunction<
  typeof api.createNotificationTemplate
>;
const updateNotificationTemplate = api.updateNotificationTemplate as jest.MockedFunction<
  typeof api.updateNotificationTemplate
>;
const archiveNotificationTemplate = api.archiveNotificationTemplate as jest.MockedFunction<
  typeof api.archiveNotificationTemplate
>;
const activateNotificationTemplate = api.activateNotificationTemplate as jest.MockedFunction<
  typeof api.activateNotificationTemplate
>;
const previewNotificationTemplate = api.previewNotificationTemplate as jest.MockedFunction<
  typeof api.previewNotificationTemplate
>;

const existingTemplate = {
  id: "t1",
  templateKey: "welcome",
  locale: "en",
  subject: "Welcome",
  body: "Hello {{name}}",
  version: 3,
  active: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

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
  listNotificationTemplates.mockResolvedValue([]);
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

  it("edits an existing template with its current version", async () => {
    listNotificationTemplates.mockResolvedValue([existingTemplate]);
    updateNotificationTemplate.mockResolvedValue({
      ...existingTemplate,
      body: "Updated {{name}}",
      version: 4,
    });
    renderEditor();

    fireEvent.click(await screen.findByRole("button", { name: /welcome · en · v3 · active/ }));
    expect(screen.getByLabelText("Template key")).toHaveValue("welcome");
    expect(screen.getByLabelText("Template key")).toBeDisabled();
    expect(screen.getByLabelText("Locale")).toHaveValue("en");

    fireEvent.change(screen.getByLabelText("Body"), {
      target: { value: "Updated {{name}}" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save template" }));

    await waitFor(() =>
      expect(updateNotificationTemplate).toHaveBeenCalledWith("t1", {
        expectedVersion: 3,
        subject: "Welcome",
        body: "Updated {{name}}",
      }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Template saved.");
    expect(createNotificationTemplate).not.toHaveBeenCalled();
  });

  it("archives the selected template and can re-activate it", async () => {
    listNotificationTemplates.mockResolvedValue([existingTemplate]);
    archiveNotificationTemplate.mockResolvedValue({
      ...existingTemplate,
      active: false,
      version: 4,
    });
    activateNotificationTemplate.mockResolvedValue({
      ...existingTemplate,
      active: true,
      version: 5,
    });
    renderEditor();

    fireEvent.click(await screen.findByRole("button", { name: /welcome · en · v3 · active/ }));
    fireEvent.click(screen.getByRole("button", { name: "Archive template" }));

    await waitFor(() => expect(archiveNotificationTemplate).toHaveBeenCalledWith("t1"));
    expect(await screen.findByRole("status")).toHaveTextContent("Template archived.");

    fireEvent.click(screen.getByRole("button", { name: "Activate template" }));
    await waitFor(() => expect(activateNotificationTemplate).toHaveBeenCalledWith("t1"));
    expect(await screen.findByRole("status")).toHaveTextContent("Template activated.");
  });

  it("supports a regional locale and normalizes numeric variables", async () => {
    previewNotificationTemplate.mockResolvedValue({
      subject: "Hi",
      body: "3 items",
      placeholders: ["count"],
    });
    renderEditor();
    fillForm({
      key: "welcome",
      subject: "Hi",
      body: "{{count}} items",
      variables: '{"count":3}',
    });
    fireEvent.change(screen.getByLabelText("Locale"), { target: { value: "en-US" } });

    fireEvent.click(screen.getByRole("button", { name: "Preview template" }));

    await waitFor(() =>
      expect(previewNotificationTemplate).toHaveBeenCalledWith({
        templateKey: "welcome",
        locale: "en-US",
        subject: "Hi",
        body: "{{count}} items",
        variablesJson: '{"count":"3"}',
      }),
    );
    expect(await screen.findByTestId("preview-body")).toHaveTextContent("3 items");
  });

  it("rejects an unsupported locale before calling the API", async () => {
    renderEditor();
    fillForm({ key: "welcome", subject: "Hi", body: "Hello" });
    fireEvent.change(screen.getByLabelText("Locale"), { target: { value: "english" } });

    fireEvent.click(screen.getByRole("button", { name: "Preview template" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Locale must look like");
    expect(previewNotificationTemplate).not.toHaveBeenCalled();
  });
});
