export function isAuthenticated(): boolean {
  return (
    typeof window !== "undefined" && !!localStorage.getItem("awap_access_token")
  );
}

export function logout(): void {
  localStorage.removeItem("awap_access_token");
  localStorage.removeItem("awap_refresh_token");
}
