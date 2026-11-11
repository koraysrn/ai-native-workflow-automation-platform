"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { setActiveTenant } from "../../lib/auth";
import { useTenantMemberships } from "../../lib/hooks/resources";
import { isForbiddenError } from "../../lib/queryClient";
import { EmptyState, ErrorState, LoadingState } from "../../components/ui/QueryState";

export default function SelectTenantPage() {
  const router = useRouter();
  const memberships = useTenantMemberships();
  const tenants = memberships.data;
  const rows = tenants ?? [];

  // A user who belongs to exactly one tenant never sees the picker.
  useEffect(() => {
    const single = tenants?.[0];
    if (tenants && tenants.length === 1 && single) {
      setActiveTenant(single.tenantId);
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- full reload clears cross-tenant caches
      window.location.assign("/dashboard");
    }
  }, [tenants]);

  // A failed lookup means the session is gone; the login page can refresh it. A 403 keeps the
  // forbidden state visible instead of bouncing the user.
  useEffect(() => {
    if (memberships.isError && !isForbiddenError(memberships.error)) {
      router.replace("/");
    }
  }, [memberships.isError, memberships.error, router]);

  const choose = (tenantId: string) => {
    setActiveTenant(tenantId);
    // Full reload clears all client-side caches, preventing stale cross-tenant data.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- intentional full reload
    window.location.assign("/dashboard");
  };

  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ fontSize: 22, fontWeight: 700 }}>Select tenant</h1>
      {memberships.isLoading && <LoadingState label="Loading tenants..." />}
      {memberships.isError && (
        <ErrorState error={memberships.error} message="Failed to load tenants." />
      )}
      {memberships.isSuccess && rows.length === 0 && (
        <EmptyState message="You do not belong to any tenant yet." />
      )}
      <div style={{ display: "flex", flexDirection: "column", gap: 8, maxWidth: 360 }}>
        {rows.map((tenant) => (
          <button
            key={tenant.tenantId}
            type="button"
            onClick={() => choose(tenant.tenantId)}
            style={{
              padding: "10px 12px",
              borderRadius: 8,
              border: "1px solid #cbd5e1",
              background: "#f8fafc",
              cursor: "pointer",
            }}
          >
            {tenant.tenantId} — {tenant.role}
          </button>
        ))}
      </div>
    </div>
  );
}
