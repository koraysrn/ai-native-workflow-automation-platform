"use client";

import type { ReactNode } from "react";
import AppShell from "../../components/layout/AppShell";
import RouteGuard from "../../components/auth/RouteGuard";

export default function AppLayout({ children }: { children: ReactNode }) {
  return (
    <RouteGuard>
      <AppShell>{children}</AppShell>
    </RouteGuard>
  );
}
