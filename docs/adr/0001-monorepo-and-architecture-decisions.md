# ADR-001: Monorepo and Architecture Decisions

- **Status:** Accepted
- **Date:** 2026-10-14

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
4. **Frontend build:** pnpm workspace rooted at `frontend/`, TypeScript 6.x in
   strict mode, Next.js 16, TanStack Query for server state. TypeScript 7.0.2
   is deferred until `typescript-eslint` supports it.
5. **AI Layer environment:** conda with `environment.yml` at the repository
   root as the single source of truth. Python 3.14 from conda-forge; LangChain
   and LangGraph 1.x are installed from PyPI via the `pip` section. Python
   3.15.0 is deferred until the ecosystem matures.
6. **License:** MIT.
7. **Project language:** English for all code, comments, commit messages, and
   documentation.

## Consequences

- A single CI pipeline can build and test all layers.
- The conda environment must be kept in sync with `environment.yml`; adding an
  AI Layer dependency means updating that file.
- The AI stack stays on LangChain/LangGraph 1.x via PyPI; Python 3.15.0 is
  deferred until conda-forge publishes 1.x packages for it or `cp315` wheels
  land on PyPI (see `COMPATIBILITY.md`).
- Personal planning documents are excluded from the repository and must never
  be committed.
