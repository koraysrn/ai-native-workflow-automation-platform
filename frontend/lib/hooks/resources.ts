import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  archiveDocumentVersion,
  approveTask,
  createDocumentUpload,
  createFormDefinition,
  createWorkflowDefinition,
  fetchAnalyticsSummary,
  fetchFormDefinition,
  fetchFormDefinitions,
  fetchOrganizationTree,
  fetchTenants,
  getDocumentDownloadUrl,
  healthCheck,
  listDocuments,
  listTasks,
  listWorkflowDefinitions,
  listWorkflowInstances,
  registerTenant,
  rejectTask,
  startWorkflowInstance,
  submitForm,
  validateWorkflowExpression,
  type CreateWorkflowPayload,
  type RegisterTenantPayload,
  type SubmitFormPayload,
} from "../api";
import { queryKeys } from "../queryKeys";

/* ---------------------------------------------------------------- tasks */

/** Tenant task list. */
export function useTasks() {
  return useQuery({ queryKey: queryKeys.tasks.list(), queryFn: listTasks });
}

/** Approve or reject a task; both decisions refresh the list the pages render. */
export function useDecideTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, approve, comment }: { id: string; approve: boolean; comment: string }) =>
      approve ? approveTask(id, comment) : rejectTask(id, comment),
    // The response is authoritative: write it into the cache before revalidating so the list
    // shows the new status immediately and a failed request leaves the cache untouched.
    onSuccess: (task) => {
      queryClient.setQueryData(queryKeys.tasks.list(), (current: (typeof task)[] | undefined) =>
        current?.map((entry) => (entry.id === task.id ? task : entry)),
      );
      return queryClient.invalidateQueries({ queryKey: queryKeys.tasks.all });
    },
  });
}

/* ----------------------------------------------------------- workflows */

/** Published and draft workflow definitions. */
export function useWorkflowDefinitions() {
  return useQuery({
    queryKey: queryKeys.workflowDefinitions.list(),
    queryFn: listWorkflowDefinitions,
  });
}

/** Workflow instances; `refetchInterval` drives the live instance screen. */
export function useWorkflowInstances(options: { refetchInterval?: number } = {}) {
  return useQuery({
    queryKey: queryKeys.workflowInstances.list(),
    queryFn: listWorkflowInstances,
    ...(options.refetchInterval ? { refetchInterval: options.refetchInterval } : {}),
  });
}

/** Starts an instance and refreshes every instance view (dashboard, instances). */
export function useStartWorkflow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      workflowDefinitionId,
      contextJson,
    }: {
      workflowDefinitionId: string;
      contextJson: string;
    }) => startWorkflowInstance(workflowDefinitionId, contextJson),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.workflowInstances.all }),
  });
}

/** Saves a designer draft and refreshes the workflows list. */
export function useCreateWorkflowDefinition() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: CreateWorkflowPayload) => createWorkflowDefinition(payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.workflowDefinitions.all }),
  });
}

/** Validates one condition expression; a designer-side dry run, no cache to invalidate. */
export function useValidateWorkflowExpression() {
  return useMutation({
    mutationFn: (expression: string) => validateWorkflowExpression(expression),
  });
}

/* ---------------------------------------------------------- documents */

/** Document versions for the scan table. */
export function useDocuments() {
  return useQuery({ queryKey: queryKeys.documents.list(), queryFn: listDocuments });
}

/** Stages a document upload (presign + PUT to object storage) and refreshes the list. */
export function useUploadDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (file: File) => {
      const slot = await createDocumentUpload(file.name, file.type, file.size);
      const response = await fetch(slot.uploadUrl, {
        method: "PUT",
        ...(file.type ? { headers: { "Content-Type": file.type } } : {}),
        body: file,
      });
      if (!response.ok) {
        throw new Error(`Upload failed with status ${response.status}`);
      }
      return slot;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.documents.all }),
  });
}

/** Archives a document version and refreshes the list. */
export function useArchiveDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (versionId: string) => archiveDocumentVersion(versionId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.documents.all }),
  });
}

/** Resolves a presigned download URL just in time. */
export function useDocumentDownloadUrl() {
  return useMutation({
    mutationFn: (fileId: string) => getDocumentDownloadUrl(fileId),
  });
}

/* -------------------------------------------------------------- forms */

/** Form definitions available to fill. */
export function useFormDefinitions() {
  return useQuery({ queryKey: queryKeys.forms.list(), queryFn: fetchFormDefinitions });
}

/** One form definition with its schema, loaded only when an id is selected. */
export function useFormDefinition(formId: string) {
  return useQuery({
    queryKey: queryKeys.forms.detail(formId),
    queryFn: () => fetchFormDefinition(formId),
    enabled: formId !== "",
  });
}

/** Submits a filled form and refreshes the form list (submission counts are server-side). */
export function useSubmitForm(formId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: SubmitFormPayload) => submitForm(formId, payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.forms.all }),
  });
}

/** Creates a form definition from the builder. */
export function useCreateFormDefinition() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ name, schemaJson }: { name: string; schemaJson: string }) =>
      createFormDefinition(name, schemaJson),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.forms.all }),
  });
}

/* ---------------------------------------------------------- analytics */

/** Analytics aggregates for the selected window. */
export function useAnalyticsSummary(from?: string, to?: string) {
  return useQuery({
    queryKey: queryKeys.analytics.summary(from, to),
    queryFn: () => fetchAnalyticsSummary(from, to),
  });
}

/* ------------------------------------------------------- organization */

/** Company -> department -> team tree. */
export function useOrganizationTree() {
  return useQuery({ queryKey: queryKeys.organizationTree.all, queryFn: fetchOrganizationTree });
}

/* ------------------------------------------------------------ tenants */

/** Tenant memberships shown by the tenant switcher. */
export function useTenantMemberships() {
  return useQuery({ queryKey: queryKeys.tenantMemberships.all, queryFn: fetchTenants });
}

/** Public health probe used by contract tests and the dashboard footer. */
export function useHealthCheck() {
  return useQuery({ queryKey: queryKeys.health.status(), queryFn: healthCheck, staleTime: 0 });
}

/** Self-service tenant registration (public flow, no cache to invalidate). */
export function useRegisterTenant() {
  return useMutation({ mutationFn: (payload: RegisterTenantPayload) => registerTenant(payload) });
}
