# Build stage: resolve and install dependencies with uv
FROM python:3.15-slim AS builder
WORKDIR /app
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
