/** @jest-environment node */

import path from "node:path";
import { PactV3 } from "@pact-foundation/pact";
import { healthCheck } from "../api";

const provider = new PactV3({
  consumer: "awap-frontend",
  provider: "awap-identity",
  dir: path.resolve(process.cwd(), "pacts"),
});

describe("awap-identity health contract", () => {
  it("verifies the health endpoint response", () => {
    provider
      .uponReceiving("a health check request")
      .withRequest({ method: "GET", path: "/actuator/health" })
      .willRespondWith({
        status: 200,
        headers: {
          "Content-Type": "application/vnd.spring-boot.actuator.v3+json",
        },
        body: { status: "UP" },
      });

    return provider.executeTest(async (mockServer) => {
      process.env.NEXT_PUBLIC_API_BASE = mockServer.url;
      const result = await healthCheck();
      expect(result.status).toBe("UP");
    });
  });
});
