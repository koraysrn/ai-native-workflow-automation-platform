import { NextResponse } from "next/server";
import { redactCspReport } from "../../../lib/csp";

/**
 * CSP violation collector (issue #183).
 *
 * Reports are redacted before they are logged or forwarded: query strings,
 * fragments, credentials and script samples never leave the process. The
 * endpoint always answers 204 so a broken report can never affect the page.
 */
export async function POST(request: Request): Promise<NextResponse> {
  let payload: unknown;
  try {
    payload = await request.json();
  } catch {
    return new NextResponse(null, { status: 204 });
  }

  const redacted = redactCspReport(payload);
  const collector = process.env.CSP_REPORT_COLLECTOR_URL;
  if (collector) {
    try {
      await fetch(collector, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(redacted),
      });
    } catch {
      // Reporting must never break the response path.
    }
  }
  console.warn("csp-violation", JSON.stringify(redacted));
  return new NextResponse(null, { status: 204 });
}
