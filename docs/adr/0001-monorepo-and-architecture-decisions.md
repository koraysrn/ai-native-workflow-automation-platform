# ADR-001: Monorepo and Architecture Decisions

- **Status:** Accepted
- **Date:** 2026-10-14
- **Amended:** 2026-10-21 — AI Layer pivoted from conda/Python 3.14 to uv/Python 3.15.0 and the frontend to TypeScript 7.0.2 (see `COMPATIBILITY.md`).

## Context

Before writing any product code, the repository organization, build tooling,
and cross-layer conventions had to be fixed so that all later phases can be
developed safely and reproducibly.

## Decision

1. **Monorepo.** All layers (backend, frontend, AI layer) and services live in
   a single repository.
2. **Backend build:** Maven multi-module project rooted at `backend/`, with a
   parent POM pinning Java 25 (`--release 25`) and Spring Boot 4.1.1 via the
   Spring Boot parent BOM.
3. **Backend package structure:** `com.awap.<module>` with Clean Architecture
   layers inside each module: `application`, `domain`, `infrastructure`,
   `interfaces`. Spring Modulith defines module boundaries; Hexagonal defines
   intra-module layers. Intra-module dependencies always point inward toward
   the domain.
4. **Frontend build:** pnpm workspace rooted at `frontend/`, TypeScript 7.0.2
   (native compiler) in strict mode, Next.js 16, TanStack Query for server
   state. ESLint runs via a TypeScript 6 alias until `typescript-eslint` ships
   TS 7 support.
5. **AI Layer environment:** uv with `ai-layer/pyproject.toml` and the
   committed `ai-layer/uv.lock` as the single source of truth. Python 3.15.0
   (uv-managed); LangChain and LangGraph 1.x are installed from PyPI.
6. **License:** MIT.
7. **Project language:** English for all code, comments, commit messages, and
   documentation.

## Consequences

- A single CI pipeline can build and test all layers.
- The uv environment must be kept in sync with `pyproject.toml`/`uv.lock`;
  adding an AI Layer dependency means updating those files and running
  `uv sync --locked`.
- The AI stack stays on LangChain/LangGraph 1.x via PyPI on Python 3.15.0;
  `cp315` wheels are verified by the pilot compatibility check (see
  `COMPATIBILITY.md`).
- Personal planning documents are excluded from the repository and must never
  be committed.
