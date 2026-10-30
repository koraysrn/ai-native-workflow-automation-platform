import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  activateNotificationTemplate,
  archiveNotificationTemplate,
  createNotificationTemplate,
  listNotificationTemplates,
  previewNotificationTemplate,
  updateNotificationTemplate,
  type CreateNotificationTemplatePayload,
  type NotificationTemplateDto,
  type UpdateNotificationTemplatePayload,
} from "../api";

export const NOTIFICATION_TEMPLATES_QUERY_KEY = ["notification-templates"] as const;

/** Loads the tenant templates for the editor. */
export function useNotificationTemplates(templateKey?: string) {
  return useQuery({
    queryKey: [...NOTIFICATION_TEMPLATES_QUERY_KEY, templateKey ?? "all"],
    queryFn: () => listNotificationTemplates(templateKey),
  });
}

function useTemplateMutation<TVariables>(
  mutationFn: (variables: TVariables) => Promise<NotificationTemplateDto>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: NOTIFICATION_TEMPLATES_QUERY_KEY });
    },
  });
}

/** Creates a template and refreshes the list. */
export function useCreateNotificationTemplate() {
  return useTemplateMutation((payload: CreateNotificationTemplatePayload) =>
    createNotificationTemplate(payload),
  );
}

/** Saves a new revision of a template. */
export function useUpdateNotificationTemplate() {
  return useTemplateMutation(
    (variables: { id: string; payload: UpdateNotificationTemplatePayload }) =>
      updateNotificationTemplate(variables.id, variables.payload),
  );
}

/** Archives a template so deliveries fall back to the built-in default. */
export function useArchiveNotificationTemplate() {
  return useTemplateMutation((id: string) => archiveNotificationTemplate(id));
}

/** Restores an archived template to the delivery path. */
export function useActivateNotificationTemplate() {
  return useTemplateMutation((id: string) => activateNotificationTemplate(id));
}

/** Renders a preview without sending a notification. */
export function usePreviewNotificationTemplate() {
  return useMutation({
    mutationFn: (payload: Parameters<typeof previewNotificationTemplate>[0]) =>
      previewNotificationTemplate(payload),
  });
}
