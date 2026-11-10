"""FastAPI application entry point for the AWAP AI Layer."""

from fastapi import FastAPI

from app.api.health import router as health_router
from app.config import SERVICE_VERSION
from app.observability import CorrelationIdMiddleware, configure_logging

configure_logging()

app = FastAPI(
    title="AWAP AI Layer",
    version=SERVICE_VERSION,
    description=(
        "Current capability: health/readiness contract and pinned "
        "LangChain/LangGraph dependencies. No agent or AI execution endpoint is "
        "implemented yet."
    ),
    openapi_url="/api/v1/openapi.json",
)

app.add_middleware(CorrelationIdMiddleware)
app.include_router(health_router, prefix="/api/v1")
