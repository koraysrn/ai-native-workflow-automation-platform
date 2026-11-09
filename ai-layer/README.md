# AWAP AI Layer

FastAPI service for the AI layer. This README describes what the service does
**today**, not what it is intended to become.

## Capability matrix

| Capability | Status | Notes |
|---|---|---|
| `GET /api/v1/health` | Implemented | Liveness + version/build metadata |
| `GET /api/v1/health/live` | Implemented | Kubernetes liveness probe |
| `GET /api/v1/health/ready` | Implemented | Dependency-aware readiness (503 when down) |
| Versioned OpenAPI contract | Implemented | [contract](../docs/openapi/ai-layer-openapi.yaml) |
| LangChain / LangGraph dependencies | Pinned, not exposed | `langchain==1.4.5`, `langgraph==1.2.15` |
| Workflow AI step execution | Not implemented | No execution endpoint exists yet |
| Agent runtime / tools | Not implemented | No agent endpoint or graph runtime |
| RAG / vector store | Not implemented | No store dependency is configured |

The backend does not call the AI layer yet. Any documentation that described
"agentic AI steps" was aspirational and is corrected in the root README.

## Endpoints

### `GET /api/v1/health`

```json
{
  "status": "UP",
  "service": "ai-layer",
  "version": "0.1.0",
  "build": "local",
  "python": "3.15.0"
}
```

`build` comes from `AI_LAYER_BUILD_SHA` (default `local`); `version` comes from
the installed package or `AI_LAYER_VERSION`.

### `GET /api/v1/health/live`

Returns the same body as `/health`. It never probes dependencies.

### `GET /api/v1/health/ready`

Returns the same body plus `dependency`:

- `none` when `AI_LAYER_DEPENDENCY_URL` is unset (the current default);
- `HTTP <status>` when the dependency answers;
- the exception name (for example `ConnectError`) and HTTP **503** with
  `status: DOWN` when the dependency is unreachable.

Configure the probe with:

| Variable | Default | Purpose |
|---|---|---|
| `AI_LAYER_DEPENDENCY_URL` | unset | Optional readiness dependency URL |
| `AI_LAYER_DEPENDENCY_TIMEOUT_SECONDS` | `2.0` | Probe timeout |
| `AI_LAYER_VERSION` | package version | Reported version |
| `AI_LAYER_BUILD_SHA` | `local` | Reported build SHA |

## Run and test

```bash
uv sync --locked
uv run uvicorn app.main:app --reload --port 8000
uv run pytest
```

OpenAPI JSON is served at `/api/v1/openapi.json`. The versioned human contract is
[`docs/openapi/ai-layer-openapi.yaml`](../docs/openapi/ai-layer-openapi.yaml).

## Compatibility

See [`COMPATIBILITY.md`](COMPATIBILITY.md) for the Python 3.15 / LangChain /
LangGraph compatibility notes. The dependencies are installed and smoke-tested,
but they are not used by the current health-only service.
