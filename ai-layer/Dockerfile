# Build stage: resolve and install dependencies with uv
FROM python:3.15-slim AS builder
WORKDIR /app
# zstandard and PyYAML have no cp315 wheels yet, so they must be compiled from source.
RUN apt-get update && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*
COPY ai-layer/pyproject.toml ai-layer/uv.lock ./
RUN pip install --no-cache-dir uv && uv sync --locked --no-dev

# Runtime stage
FROM python:3.15-slim
COPY --from=builder /app/.venv /app/.venv
COPY ai-layer/app /app/app
WORKDIR /app
ENV PATH=/app/.venv/bin:$PATH
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
