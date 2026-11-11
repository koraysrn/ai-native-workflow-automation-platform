"use client";

import { QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { createQueryClient } from "../../lib/queryClient";

/**
 * Application-wide TanStack Query provider (issue #202). It lives in the root layout so every route
 * group — including the tenant switcher outside `(app)` — shares one cache and one retry policy.
 */
export default function QueryProvider({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => createQueryClient());
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
