# Compatibility Matrix

This matrix records the fixed versions and the results of the pilot
compatibility verification performed before development started. It is updated
on every version change (see `CLAUDE.md` — Version Compatibility Rules).

## Fixed Versions

| Component | Fixed Version | Verified | Notes |
|---|---|---|---|
| Java | 25 (LTS, Temurin) | ✅ | `mvn verify` BUILD SUCCESS with Spring Boot 4.1.1 |
| Spring Boot | 4.1.1 | ✅ | Parent BOM; `--release 25` compile |
| Maven | 3.9.x | ✅ | 3.9.9 used for the pilot test; Maven Wrapper committed |
| TypeScript | 6.x | ✅ | Chosen for ESLint compatibility; upgrade to 7.0.2 when `typescript-eslint` supports it |
| Next.js | 16.4.x | ✅ | `next build` (Turbopack) OK |
| React | 19.3.x | ✅ | |
| Node.js | 24 LTS | ✅ | |
| pnpm | 12.x | ✅ | |
| Python | 3.15.0 (uv) | ✅ | uv-managed with committed `uv.lock`; cp315 wheels verified |
| FastAPI | 0.135.x | ✅ | installed via `uv` from PyPI |
| LangChain | 1.4.x | ✅ | installed via `uv` from PyPI |
| LangGraph | 1.2.x | ✅ | installed via `uv` from PyPI |

## AI Layer Notes

- The AI Layer environment is managed **with uv**; `ai-layer/pyproject.toml`
  and the committed `ai-layer/uv.lock` are the single source of truth.
- Python is pinned to 3.15.0 via `[tool.uv]`; C-based dependencies
  (`numpy`, `orjson`, `pydantic-core`) are verified by the pilot compatibility
  check (`ai-layer/compat_check.py`).
- `langchain` (1.4.x) and `langgraph` (1.2.x) are installed from PyPI.
- `environment.yml` was removed; the AI layer no longer depends on conda.

## Frontend Notes

- TypeScript 6.x is used so that ESLint (`typescript-eslint`) works end to end.
- TypeScript 7.0.2 (native compiler) passes `tsc --noEmit` and `next build`,
  but `typescript-eslint` does not support TS 7.0 yet
  (typescript-eslint#10940).
- When `typescript-eslint` ships TS 7 support, the project will upgrade to 7.0.2.

## Version Upgrade Policy

- Upgrades are performed only through Renovate / Dependabot after CI passes.
- On every upgrade: OWASP Dependency Scan, `mvn verify`,
  `pnpm build && pnpm typecheck && pnpm lint`, `ruff check`, and
  `uv sync --locked` must pass.
- A PR containing an incompatible version is automatically rejected in CI.
