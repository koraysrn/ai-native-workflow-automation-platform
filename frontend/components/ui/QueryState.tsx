"use client";

import type { ReactNode } from "react";
import { isForbiddenError } from "../../lib/queryClient";

/** Standard loading state used by every server-data screen. */
export function LoadingState({ label = "Loading…" }: { label?: string | undefined }) {
  return (
    <p role="status" aria-live="polite" data-testid="loading-state">
      {label}
    </p>
  );
}

/**
 * Standard error state. A 403 renders the forbidden copy (the user is authenticated but lacks the
 * permission) and is never retried by the query layer, so this state is terminal.
 */
export function ErrorState({
  error,
  message = "Something went wrong.",
  forbiddenMessage = "You do not have permission to view this.",
}: {
  error?: unknown;
  message?: string | undefined;
  forbiddenMessage?: string | undefined;
}) {
  if (isForbiddenError(error)) {
    return (
      <p role="alert" data-testid="forbidden-state">
        {forbiddenMessage}
      </p>
    );
  }
  return (
    <p role="alert" data-testid="error-state">
      {message}
    </p>
  );
}

/** Standard empty state for a successful but empty collection. */
export function EmptyState({
  message,
  testId = "empty-state",
}: {
  message: string;
  testId?: string;
}) {
  return (
    <p data-testid={testId} style={{ color: "#6b7280" }}>
      {message}
    </p>
  );
}

/**
 * Small helper for the common "loading / forbidden / error / empty / content" decision so pages do
 * not reimplement slightly different branches.
 */
export function QueryState({
  isLoading,
  isError,
  error,
  isEmpty,
  loadingLabel,
  errorMessage,
  emptyMessage,
  children,
}: {
  isLoading: boolean;
  isError: boolean;
  error?: unknown;
  isEmpty?: boolean;
  loadingLabel?: string | undefined;
  errorMessage?: string | undefined;
  emptyMessage?: string | undefined;
  children: ReactNode;
}) {
  if (isLoading) {
    return <LoadingState label={loadingLabel} />;
  }
  if (isError) {
    return <ErrorState error={error} message={errorMessage} />;
  }
  if (isEmpty && emptyMessage) {
    return <EmptyState message={emptyMessage} />;
  }
  return <>{children}</>;
}
