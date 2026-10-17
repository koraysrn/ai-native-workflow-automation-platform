export function isAuthenticated(): boolean {
  if (typeof document === "undefined") {
    return false;
  }
  return document.cookie.includes("awap_session=1");
}

export function getActiveTenant(): string | null {
  if (typeof document === "undefined") {
    return null;
  }
  const match = document.cookie.match(/(?:^|;\s*)awap_tenant=([^;]*)/);
  const value = match?.[1];
  return value ? decodeURIComponent(value) : null;
}

export function setActiveTenant(tenantId: string): void {
  document.cookie =
    `awap_tenant=${encodeURIComponent(tenantId)}; path=/; max-age=604800; SameSite=Lax`;
}

export function clearActiveTenant(): void {
  document.cookie = "awap_tenant=; path=/; max-age=0";
}

export async function logout(): Promise<void> {
  try {
    await fetch(`${process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080"}/api/v1/auth/logout`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: "{}",
    });
  } catch {
    // best-effort logout; cookies will be cleared on next successful auth
  }
  // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- full reload clears session cookies and re-runs the middleware auth check
  window.location.href = "/";
}
