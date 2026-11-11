import { QueryClient } from "@tanstack/react-query";
import { errorStatus } from "./api";

/** How long a cached response is considered fresh before a background revalidation. */
export const STALE_TIME_MS = 30_000;

/** How many times a 5xx/network failure is retried before the error state renders. */
export const MAX_RETRIES = 2;

/**
 * Retries only failures that can succeed on a retry. A 4xx is a deterministic answer (403 is not
 * transient, 404 will not appear on the second call), so retrying it would hammer the API and delay
 * the forbidden/error state. 5xx and transport errors get a bounded retry budget.
 */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  const status = errorStatus(error);
  if (status !== undefined) {
    return status >= 500 && failureCount < MAX_RETRIES;
  }
  return failureCount < MAX_RETRIES;
}

/** True when the request was rejected by authorization, not by a transient failure. */
export function isForbiddenError(error: unknown): boolean {
  return errorStatus(error) === 403;
}

/**
 * One QueryClient policy for the whole app: shared stale time, bounded retries, mutations never
 * retried (a mutation may be charged/notified twice if replayed blindly) and no refetch on focus
 * (the pages choose their own polling where live data matters).
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: STALE_TIME_MS,
        retry: shouldRetry,
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: 0,
      },
    },
  });
}
