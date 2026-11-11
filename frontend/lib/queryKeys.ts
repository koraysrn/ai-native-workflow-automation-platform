/**
 * Query-key factory (issue #202). Every server resource is addressed through this object so a
 * mutation invalidates the exact keys its readers use and no page invents an ad-hoc array.
 *
 * Keys mirror the historical literals (`["tasks"]`, `["documents"]`, …) so the SSE cache writer in
 * `useDocumentScanStatus` and in-flight caches keep matching.
 */
export const queryKeys = {
  health: {
    status: () => ["health", "status"] as const,
  },
  tasks: {
    all: ["tasks"] as const,
    list: () => ["tasks"] as const,
    mine: () => ["tasks", "mine"] as const,
  },
  workflowDefinitions: {
    all: ["workflow-definitions"] as const,
    list: () => ["workflow-definitions"] as const,
  },
  workflowInstances: {
    all: ["instances"] as const,
    list: () => ["instances"] as const,
  },
  documents: {
    all: ["documents"] as const,
    list: () => ["documents"] as const,
  },
  forms: {
    all: ["forms"] as const,
    list: () => ["forms"] as const,
    detail: (id: string) => ["forms", "detail", id] as const,
  },
  analytics: {
    all: ["analytics-summary"] as const,
    summary: (from?: string, to?: string) =>
      ["analytics-summary", from ?? null, to ?? null] as const,
  },
  organizationTree: {
    all: ["organization-tree"] as const,
  },
  notifications: {
    all: ["notifications"] as const,
    list: (unreadOnly = false) => ["notifications", unreadOnly] as const,
    unreadCount: () => ["notifications", "unread-count"] as const,
  },
  notificationTemplates: {
    all: ["notification-templates"] as const,
    list: (templateKey?: string) => ["notification-templates", templateKey ?? "all"] as const,
  },
  tenantMemberships: {
    all: ["tenant-memberships"] as const,
  },
} as const;
