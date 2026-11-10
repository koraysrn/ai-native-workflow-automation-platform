# ADR-0006: OpenAPI contract validation and the codegen decision

- **Status:** Accepted
- **Date:** 2026-11-10
- **Issue:** #192

## Context

The REST contract lives in [`docs/openapi/awap-openapi.yaml`](../openapi/awap-openapi.yaml). It is
large, passes Spectral, and is used by the frontend Pact consumer tests, but at least the tenant
invitation acceptance endpoint was missing. A contract that is not checked against the controllers
drifts silently and breaks generated or hand-written clients.

The constitution requires API-first design, OpenAPI, mandatory contract testing and no breaking
changes. The backend DTOs are Java records mapped with MapStruct; the frontend uses TypeScript.

## Decision

1. **The YAML document remains the single published contract.** Changes to an endpoint and its
   spec land in the same pull request.
2. **Drift is blocked mechanically.** `scripts/openapi-controller-coverage.mjs` derives the endpoint
   inventory from the Spring annotations and fails when a controller mapping is missing from the
   spec (or a `/api/` spec path has no controller). Operational paths such as `/actuator/health`
   are documented but excluded from the controller comparison.
3. **Breaking changes are blocked.** `scripts/openapi-breaking-change.mjs` compares the PR document
   with the base branch and fails on removed paths/methods/status codes, changed types, removed
   enum values, new required request properties, removed response properties and removed security.
4. **Server DTO code generation is not adopted now.** Generating Java records from the schema would
   duplicate the domain-facing records and MapStruct mappers, force a large refactor and make the
   Pact/web-layer tests harder to evolve. The controller inventory, schema lint and breaking-change
   gate give the same drift protection with less risk.
5. **Client generation is adopted incrementally.** The generated TypeScript client may be
   introduced module by module once the frontend migration to TanStack Query is complete
   (issue #202); it must compile and pass the Pact contract tests before replacing hand-written
   calls. No generated server code is committed.

## Consequences

- A missing endpoint or a schema-incompatible change fails the `API Lint` job before merge.
- Additive changes remain cheap: new paths, optional properties and new response codes pass.
- Examples and security schemes are added to new endpoints; the global `bearerAuth` scheme and the
  explicit public opt-out (`security: []`) are validated by the contract test.
- Revisiting server DTO generation requires a superseding ADR and a compatibility plan.
