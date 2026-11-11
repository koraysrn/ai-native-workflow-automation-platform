import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "../api";
import {
  createQueryClient,
  isForbiddenError,
  MAX_RETRIES,
  shouldRetry,
  STALE_TIME_MS,
} from "../queryClient";
import { queryKeys } from "../queryKeys";

describe("shouldRetry", () => {
  it("never retries a 4xx, including 403", () => {
    expect(shouldRetry(0, new ApiError(403, "forbidden"))).toBe(false);
    expect(shouldRetry(0, new ApiError(404, "missing"))).toBe(false);
    expect(shouldRetry(0, new ApiError(400, "bad request"))).toBe(false);
  });

  it("retries a 5xx only within the retry budget", () => {
    expect(shouldRetry(0, new ApiError(500, "boom"))).toBe(true);
    expect(shouldRetry(MAX_RETRIES - 1, new ApiError(503, "busy"))).toBe(true);
    expect(shouldRetry(MAX_RETRIES, new ApiError(500, "boom"))).toBe(false);
  });

  it("retries transport errors within the same budget", () => {
    expect(shouldRetry(0, new Error("network down"))).toBe(true);
    expect(shouldRetry(MAX_RETRIES, new Error("network down"))).toBe(false);
  });
});

describe("isForbiddenError", () => {
  it("only matches a 403 ApiError", () => {
    expect(isForbiddenError(new ApiError(403, "forbidden"))).toBe(true);
    expect(isForbiddenError(new ApiError(401, "unauthorized"))).toBe(false);
    expect(isForbiddenError(new Error("403"))).toBe(false);
    expect(isForbiddenError(undefined)).toBe(false);
  });
});

describe("createQueryClient", () => {
  it("sets one cache/retry policy for the whole app", () => {
    const client = createQueryClient();
    const options = client.getDefaultOptions();
    expect(options.queries?.staleTime).toBe(STALE_TIME_MS);
    expect(options.queries?.refetchOnWindowFocus).toBe(false);
    expect(options.mutations?.retry).toBe(0);
    const retry = options.queries?.retry as (count: number, error: unknown) => boolean;
    expect(retry(0, new ApiError(403, "forbidden"))).toBe(false);
    expect(retry(0, new ApiError(500, "boom"))).toBe(true);
  });

  it("revalidates a cached query in the background instead of blocking the first render", async () => {
    const client = createQueryClient();
    const queryFn = jest.fn(async () => ["fresh"]);
    client.setQueryData(queryKeys.tasks.list(), ["cached"]);

    // A cached entry is returned immediately…
    expect(client.getQueryData(queryKeys.tasks.list())).toEqual(["cached"]);
    // …and a refetch replaces it without clearing the cache first.
    await client.fetchQuery({ queryKey: queryKeys.tasks.list(), queryFn, staleTime: 0 });
    expect(queryFn).toHaveBeenCalledTimes(1);
    expect(client.getQueryData(queryKeys.tasks.list())).toEqual(["fresh"]);
  });

  it("gives a 403 exactly one attempt and a 500 the bounded budget", async () => {
    const forbidden = new QueryClient({
      defaultOptions: { queries: { retry: shouldRetry, retryDelay: 0 } },
    });
    const forbiddenFn = jest.fn(async () => {
      throw new ApiError(403, "forbidden");
    });
    await expect(
      forbidden.fetchQuery({ queryKey: ["forbidden"], queryFn: forbiddenFn }),
    ).rejects.toMatchObject({ status: 403 });
    expect(forbiddenFn).toHaveBeenCalledTimes(1);

    const failing = new QueryClient({
      defaultOptions: { queries: { retry: shouldRetry, retryDelay: 0 } },
    });
    const failingFn = jest.fn(async () => {
      throw new ApiError(500, "boom");
    });
    await expect(
      failing.fetchQuery({ queryKey: ["failing"], queryFn: failingFn }),
    ).rejects.toMatchObject({ status: 500 });
    expect(failingFn).toHaveBeenCalledTimes(MAX_RETRIES + 1);
  });

  it("marks related keys stale on invalidation", async () => {
    const client = createQueryClient();
    client.setQueryData(queryKeys.notifications.list(false), [{ id: "n1" }]);
    await client.invalidateQueries({ queryKey: queryKeys.notifications.all });
    expect(client.getQueryState(queryKeys.notifications.list(false))?.isInvalidated).toBe(true);
  });
});
