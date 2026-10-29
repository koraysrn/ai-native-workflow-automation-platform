import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  fetchUnreadNotificationCount,
  listNotifications,
  markNotificationRead,
  markNotificationUnread,
  type NotificationDeliveryDto,
} from "../api";

export const NOTIFICATIONS_QUERY_KEY = ["notifications"] as const;
export const UNREAD_COUNT_QUERY_KEY = ["notifications", "unread-count"] as const;

/** Loads the tenant notification inbox. */
export function useNotifications() {
  return useQuery({
    queryKey: NOTIFICATIONS_QUERY_KEY,
    queryFn: () => listNotifications(),
  });
}

/** Loads the unread badge count. */
export function useUnreadNotificationCount() {
  return useQuery({
    queryKey: UNREAD_COUNT_QUERY_KEY,
    queryFn: fetchUnreadNotificationCount,
  });
}

/** Marks one notification read and refreshes the list and the badge. */
export function useMarkNotificationRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => markNotificationRead(id),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: UNREAD_COUNT_QUERY_KEY }),
      ]);
    },
  });
}

/** Restores one notification to unread and refreshes the list and the badge. */
export function useMarkNotificationUnread() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => markNotificationUnread(id),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: UNREAD_COUNT_QUERY_KEY }),
      ]);
    },
  });
}

/** Read-state toggle used by the inbox action button. */
export function useToggleNotificationRead() {
  const markRead = useMarkNotificationRead();
  const markUnread = useMarkNotificationUnread();
  return {
    toggle: (notification: NotificationDeliveryDto) => {
      if (notification.read) {
        markUnread.mutate(notification.id);
      } else {
        markRead.mutate(notification.id);
      }
    },
    pendingId: markRead.isPending ? markRead.variables : markUnread.isPending ? markUnread.variables : null,
    error: markRead.error ?? markUnread.error,
  };
}
