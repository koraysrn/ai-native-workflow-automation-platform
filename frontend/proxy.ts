import { NextResponse, type NextRequest } from "next/server";

export function proxy(request: NextRequest) {
  const session = request.cookies.get("awap_session")?.value;
  if (session !== "1") {
    return NextResponse.redirect(new URL("/", request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: [
    "/dashboard/:path*",
    "/workflows/:path*",
    "/tasks/:path*",
    "/instances/:path*",
    "/organizations/:path*",
    "/forms/:path*",
    "/documents/:path*",
    "/analytics/:path*",
  ],
};
