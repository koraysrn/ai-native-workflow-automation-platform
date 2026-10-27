import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactElement } from "react";
import type { NotificationDeliveryDto } from "../../../lib/api";
import * as api from "../../../lib/api";
import NotificationInbox from "../NotificationInbox";

jest.mock("../../../lib/api", () => ({
  listNotifications: jest.fn(),
  fetchUnreadNotificationCount: jest.fn(),
  markNotificationRead: jest.fn(),
  markNotificationUnread: jest.fn(),
}));

const listNotifications = api.listNotifications as jest.MockedFunction<
  typeof api.listNotifications
>;
const fetchUnreadNotificationCount = api.fetchUnreadNotificationCount as jest.MockedFunction<
  typeof api.fetchUnreadNotificationCount
>;
const markNotificationRead = api.markNotificationRead as jest.MockedFunction<
  typeof api.markNotificationRead
>;
const markNotificationUnread = api.markNotificationUnread as jest.MockedFunction<
  typeof api.markNotificationUnread
>;

function notification(overrides: Partial<NotificationDeliveryDto> = {}): NotificationDeliveryDto {
  return {
    id: "n1",
    channel: "WEB",
    recipient: "user@example.com",
    templateKey: "welcome",
    status: "SENT",
    attempts: 1,
    lastError: null,
    createdAt: "2026-02-01T10:00:00Z",
    read: false,
    readAt: null,
    ...overrides,
  };
}

function renderInbox(ui: ReactElement = <NotificationInbox />) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

beforeEach(() => {
  jest.resetAllMocks();
});

describe("NotificationInbox", () => {
  it("renders the unread badge with an accessible label", async () => {
    listNotifications.mockResolvedValue([notification()]);
    fetchUnreadNotificationCount.mockResolvedValue({ count: 1 });

    renderInbox();

    const badge = await screen.findByTestId("unread-badge");
    await waitFor(() => expect(badge).toHaveTextContent("1"));
    expect(badge).toHaveAttribute("aria-label", "Unread notifications: 1");
    expect(badge).toHaveAttribute("role", "status");
    expect(await screen.findByText("Unread")).toBeInTheDocument();
  });

  it("marks a notification read, updates the badge and exposes the unread action", async () => {
    let read = false;
    listNotifications.mockImplementation(async () => [
      notification({ read, readAt: read ? "2026-02-01T11:00:00Z" : null }),
    ]);
    fetchUnreadNotificationCount.mockImplementation(async () => ({ count: read ? 0 : 1 }));
    markNotificationRead.mockImplementation(async () => {
      read = true;
      return notification({ read: true, readAt: "2026-02-01T11:00:00Z" });
    });

    renderInbox();

    const markReadButton = await screen.findByRole("button", {
      name: 'Mark notification "welcome" as read',
    });
    fireEvent.click(markReadButton);

    await waitFor(() => expect(markNotificationRead).toHaveBeenCalledWith("n1"));
    await waitFor(() =>
      expect(screen.getByTestId("unread-badge")).toHaveAttribute(
        "aria-label",
        "Unread notifications: 0",
      ),
    );
    expect(await screen.findByText("Read")).toBeInTheDocument();
    expect(
      await screen.findByRole("button", { name: 'Mark notification "welcome" as unread' }),
    ).toBeInTheDocument();
  });

  it("restores a notification to unread and increases the badge", async () => {
    let read = true;
    listNotifications.mockImplementation(async () => [
      notification({ read, readAt: read ? "2026-02-01T11:00:00Z" : null }),
    ]);
    fetchUnreadNotificationCount.mockImplementation(async () => ({ count: read ? 0 : 1 }));
    markNotificationUnread.mockImplementation(async () => {
      read = false;
      return notification({ read: false, readAt: null });
    });

    renderInbox();

    const markUnreadButton = await screen.findByRole("button", {
      name: 'Mark notification "welcome" as unread',
    });
    fireEvent.click(markUnreadButton);

    await waitFor(() => expect(markNotificationUnread).toHaveBeenCalledWith("n1"));
    await waitFor(() => expect(screen.getByTestId("unread-badge")).toHaveTextContent("1"));
    expect(await screen.findByText("Unread")).toBeInTheDocument();
  });

  it("shows an empty state when the tenant has no notifications", async () => {
    listNotifications.mockResolvedValue([]);
    fetchUnreadNotificationCount.mockResolvedValue({ count: 0 });

    renderInbox();

    expect(await screen.findByText("You have no notifications.")).toBeInTheDocument();
    expect(screen.getByTestId("unread-badge")).toHaveTextContent("0");
    expect(screen.queryAllByRole("button")).toHaveLength(0);
  });

  it("surfaces a load failure as an alert", async () => {
    listNotifications.mockRejectedValue(new Error("boom"));
    fetchUnreadNotificationCount.mockRejectedValue(new Error("boom"));

    renderInbox();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Notifications could not be loaded.",
    );
  });

  it("labels notifications without a template by their channel", async () => {
    listNotifications.mockResolvedValue([notification({ templateKey: null, channel: "SMS" })]);
    fetchUnreadNotificationCount.mockResolvedValue({ count: 1 });

    renderInbox();

    expect(
      await screen.findByRole("button", { name: 'Mark notification "SMS" as read' }),
    ).toBeInTheDocument();
  });
});
