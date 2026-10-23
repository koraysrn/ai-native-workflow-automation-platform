# API Deprecation Policy

Operational implementation of the deprecation rule in
[`CLAUDE.md`](../CLAUDE.md).

## Rules

1. API versions are URI-based: `/api/v1/...`. Breaking changes require a new
   major version, never a silent change to an existing version.
2. A deprecated endpoint is advertised with the `Deprecation: true` response
   header and a `Sunset` header carrying the removal date (RFC 8594).
3. The old version remains supported for at least **6 months** after the
   deprecation announcement.
4. Announcements happen at least **2 versions** before removal.

## Mechanism

A `DeprecationFilter` reads `awap.api.deprecated-paths` (path prefix → Sunset
date) and injects the headers for matching requests. Adding a path to the map
is a visible, reviewable change that triggers the deprecation clock.
