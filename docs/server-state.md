# Server state on TanStack Query (issue #202)

Every server response in the SPA goes through one TanStack Query cache. Pages do not call `fetch`:
they render a hook from [`frontend/lib/hooks/resources.ts`](../frontend/lib/hooks/resources.ts) and
one of the standard state components.

## Query-key factory

[`frontend/lib/queryKeys.ts`](../frontend/lib/queryKeys.ts) is the only place a cache key is built:

| Resource | Key | Invalidation |
|---|---|---|
| Tasks | `["tasks"]`, `["tasks","mine"]` | `useDecideTask` → `tasks.all` |
| Workflow definitions | `["workflow-definitions"]` | `useCreateWorkflowDefinition` → `workflowDefinitions.all` |
| Workflow instances | `["instances"]` | `useStartWorkflow` → `workflowInstances.all` |
| Documents | `["documents"]` | upload/archive → `documents.all`; SSE events write in place |
| Forms | `["forms"]`, `["forms","detail",id]` | create/submit → `forms.all` |
| Analytics | `["analytics-summary", from, to]` | read-only |
| Organization tree | `["organization-tree"]` | read-only |
| Notifications | `["notifications", unreadOnly]`, `["notifications","unread-count"]` | read/unread → `notifications.all` |
| Notification templates | `["notification-templates", templateKey]` | create/update/archive/activate → `notificationTemplates.all` |
| Tenant memberships | `["tenant-memberships"]` | read-only |

Filtered variants share the resource prefix, so invalidating the `all` key reaches every variant.
The keys keep the historical literal prefixes (`["tasks"]`, `["documents"]`, …) so the SSE cache
writer in `useDocumentScanStatus` and any warm cache keep matching.

## Retry, caching and auth behaviour

[`frontend/lib/queryClient.ts`](../frontend/lib/queryClient.ts) defines the single policy:

- `staleTime` 30 s: a cached page renders immediately and revalidates in the background.
- A **4xx is never retried** (403 renders the forbidden state at once; a 404 will not appear on the
  second call). 5xx and transport errors retry twice, then render the error state.
- Mutations never retry: a replay could charge or notify twice.
- `refetchOnWindowFocus` is off; screens that need live data opt in (instances poll every 5 s,
  documents stream SSE with a polling fallback).

`frontend/lib/api.ts` refreshes the access token **once** across concurrent 401s (`refreshSession`
holds one in-flight promise), retries each affected request once and redirects to login only when the
refresh itself fails. Errors are `ApiError` instances carrying the HTTP status, which is what the
retry policy and the forbidden state read.

## Loading, error, empty

[`frontend/components/ui/QueryState.tsx`](../frontend/components/ui/QueryState.tsx) provides
`LoadingState`, `ErrorState` (with a dedicated 403 `forbidden-state`), `EmptyState` and a
`QueryState` helper. Every page renders these instead of ad-hoc `<p>` labels, and each carries a
stable `data-testid` (`loading-state`, `error-state`, `forbidden-state`, `empty-state`).

## Enforcement

`frontend/lib/__tests__/queryKeys.test.ts` scans `app/` and `components/` and fails when a
`useQuery`/`useMutation` call or a direct `fetch` appears outside `lib/` (the CSP report route is the
one documented exception). `resources.test.tsx` covers cache sharing, mutation invalidation, a failed
mutation leaving the cache intact, and the disabled detail query; `apiAuth.test.ts` covers the
single-flight refresh, the 403 path and the 204 body; `queryClient.test.ts` covers the retry matrix.
