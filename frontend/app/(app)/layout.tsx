"use client";

import type { ReactNode } from "react";
import { useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import AppShell from "../../components/layout/AppShell";
import RouteGuard from "../../components/auth/RouteGuard";

export default function AppLayout({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => new QueryClient());

  return (
    <QueryClientProvider client={queryClient}>
      <RouteGuard>
        <AppShell>{children}</AppShell>
      </RouteGuard>
    </QueryClientProvider>
  );
}
