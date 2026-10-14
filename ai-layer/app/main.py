"""FastAPI application entry point for the AWAP AI Layer."""

from fastapi import FastAPI

from app.api.health import router as health_router

app = FastAPI(title="AWAP AI Layer", version="0.1.0")

app.include_router(health_router, prefix="/api/v1")
