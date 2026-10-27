"use client";

import type { NotificationDeliveryDto } from "../../lib/api";
import {
  useNotifications,
  useToggleNotificationRead,
  useUnreadNotificationCount,
} from "../../lib/hooks/useNotifications";

/** Human readable label for a delivery, used in the action's accessible name. */
export function notificationLabel(notification: NotificationDeliveryDto): string {
  return notification.templateKey ?? notification.channel;
}

/** Tenant notification inbox with an unread badge and read/unread actions. */
export default function NotificationInbox() {
  const notifications = useNotifications();
  const unreadCount = useUnreadNotificationCount();
  const { toggle, pendingId, error } = useToggleNotificationRead();

  const count = unreadCount.data?.count ?? 0;
  const items = notifications.data ?? [];

  return (
    <section aria-labelledby="notification-inbox-heading" style={{ maxWidth: 720 }}>
      <header style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <h1 id="notification-inbox-heading" style={{ fontSize: 24, fontWeight: 700 }}>
          Notifications
        </h1>
        <span
          role="status"
          aria-live="polite"
          aria-label={`Unread notifications: ${count}`}
          data-testid="unread-badge"
          style={{
            minWidth: 28,
            padding: "2px 8px",
            borderRadius: 999,
            background: count > 0 ? "#4338ca" : "#e2e8f0",
            color: count > 0 ? "#ffffff" : "#334155",
            fontWeight: 700,
            textAlign: "center",
          }}
        >
          {count}
        </span>
      </header>

      {notifications.isLoading || unreadCount.isLoading ? (
        <p role="status">Loading notifications…</p>
      ) : null}

      {notifications.isError || unreadCount.isError ? (
        <p role="alert">Notifications could not be loaded.</p>
      ) : null}

      {error ? <p role="alert">The notification could not be updated.</p> : null}

      {!notifications.isLoading && !notifications.isError && items.length === 0 ? (
        <p>You have no notifications.</p>
      ) : null}

      <ul style={{ listStyle: "none", padding: 0, display: "grid", gap: 8 }}>
        {items.map((notification) => {
          const label = notificationLabel(notification);
          const action = notification.read ? "unread" : "read";
          return (
            <li
              key={notification.id}
              data-testid={`notification-${notification.id}`}
              style={{
                border: "1px solid #e2e8f0",
                borderRadius: 8,
                padding: 12,
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                gap: 12,
              }}
            >
              <div>
                <p style={{ margin: 0, fontWeight: notification.read ? 400 : 600 }}>{label}</p>
                <p style={{ margin: "4px 0 0", color: "#475569", fontSize: 13 }}>
                  {notification.recipient} ·{" "}
                  {new Date(notification.createdAt).toLocaleString("en-GB")} ·{" "}
                  <span data-testid={`state-${notification.id}`}>
                    {notification.read ? "Read" : "Unread"}
                  </span>
                </p>
              </div>
              <button
                type="button"
                onClick={() => toggle(notification)}
                disabled={pendingId === notification.id}
                aria-label={`Mark notification "${label}" as ${action}`}
                style={{
                  padding: "6px 10px",
                  borderRadius: 6,
                  border: "1px solid #cbd5e1",
                  background: "#ffffff",
                  cursor: "pointer",
                }}
              >
                {notification.read ? "Mark as unread" : "Mark as read"}
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
