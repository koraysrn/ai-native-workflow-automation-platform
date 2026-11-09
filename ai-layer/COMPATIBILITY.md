# AI Layer Compatibility

Pilot compatibility report for the AI layer on Python 3.15.0.

## Result

✅ All C-based dependencies compile/import and run a smoke assertion on Python 3.15.0.

| Dependency | Kind | Verified |
|---|---|---|
| numpy | C-based | ✅ matrix multiplication |
| orjson | C-based | ✅ JSON round-trip |
| pydantic-core | C-based | ✅ `SchemaValidator` available |
| fastapi | pure | ✅ via `uv sync --locked` |
| uvicorn | pure | ✅ via `uv sync --locked` |
| langchain | pure | ✅ 1.4.5 |
| langgraph | pure | ✅ 1.2.15 |

## How to reproduce

```bash
uv sync --locked
uv run python compat_check.py
```

## Notes

- Python is pinned to 3.15.0 via `ai-layer/.python-version` and `requires-python`.
- `uv.lock` is committed and CI enforces `uv sync --locked`.
- `environment.yml` (conda) was removed; the layer is now uv-managed.

## Runtime contract and limits (issue #185)

- The only implemented API surface is the health contract:
  `/api/v1/health`, `/api/v1/health/live` and `/api/v1/health/ready`.
- The versioned contract is
  [`docs/openapi/ai-layer-openapi.yaml`](../docs/openapi/ai-layer-openapi.yaml).
  A pytest contract test fails when the documented paths, status/body or
  version drift from the running app.
- LangChain 1.4.5 and LangGraph 1.2.15 are installed and compatibility-tested on
  Python 3.15, but **no agent, AI execution, tool or RAG endpoint is
  implemented**. Documentation must not claim otherwise.
- `/api/v1/health/ready` is dependency-aware: with
  `AI_LAYER_DEPENDENCY_URL` set, an unreachable dependency produces HTTP 503
  with `status: DOWN` and the dependency exception name.
- Python 3.15 compatibility for the C-based dependencies is verified by
  `compat_check.py`; a Python minor-version bump requires re-running it and
  updating `uv.lock`.
