export function isAuthenticated(): boolean {
  if (typeof document === "undefined") {
    return false;
  }
  return document.cookie.includes("awap_session=1");
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
