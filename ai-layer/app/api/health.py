"""Health check endpoints for the AWAP AI Layer.

`/health` and `/health/live` are liveness endpoints: they only prove that the
process can answer. `/health/ready` is the readiness endpoint: when
`AI_LAYER_DEPENDENCY_URL` is configured it probes that dependency and returns
503 while the dependency is unreachable.
"""

from __future__ import annotations

import httpx
from fastapi import APIRouter, Response
from pydantic import BaseModel, Field

from app.config import (
    BUILD_SHA,
    DEPENDENCY_TIMEOUT_SECONDS,
    DEPENDENCY_URL,
    PYTHON_VERSION,
    SERVICE_NAME,
    SERVICE_VERSION,
)

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    """Liveness/readiness status shared by every health endpoint."""

    status: str = Field(examples=["UP"])
    service: str = Field(examples=[SERVICE_NAME])
    version: str = Field(examples=[SERVICE_VERSION])
    build: str = Field(examples=[BUILD_SHA])
    python: str = Field(examples=[PYTHON_VERSION])


class ReadinessResponse(HealthResponse):
    """Readiness adds the dependency probe result."""

    dependency: str = Field(examples=["none", "HTTP 200", "ConnectError"])


def _health() -> HealthResponse:
    return HealthResponse(
        status="UP",
        service=SERVICE_NAME,
        version=SERVICE_VERSION,
        build=BUILD_SHA,
        python=PYTHON_VERSION,
    )


@router.get(
    "/health",
    response_model=HealthResponse,
    summary="Liveness and build metadata",
    description="Returns the documented liveness contract for the AI layer.",
)
def health() -> HealthResponse:
    """Return the service liveness status and build metadata."""
    return _health()


@router.get(
    "/health/live",
    response_model=HealthResponse,
    summary="Kubernetes liveness probe",
    description="Liveness only; it never probes downstream dependencies.",
)
def liveness() -> HealthResponse:
    """Return liveness without checking dependencies."""
    return _health()


@router.get(
    "/health/ready",
    response_model=ReadinessResponse,
    summary="Kubernetes readiness probe",
    description=(
        "Checks the configured dependency. Returns 503 with status DOWN while "
        "the dependency is unreachable."
    ),
    responses={
        503: {
            "model": ReadinessResponse,
            "description": "A required dependency is unavailable.",
        }
    },
)
async def readiness(response: Response) -> ReadinessResponse:
    """Return readiness, probing the configured dependency when present."""
    healthy = True
    dependency = "none"

    if DEPENDENCY_URL is not None:
        try:
            async with httpx.AsyncClient(timeout=DEPENDENCY_TIMEOUT_SECONDS) as client:
                result = await client.get(DEPENDENCY_URL)
            dependency = f"HTTP {result.status_code}"
            healthy = result.status_code < 500
        except Exception as exc:  # noqa: BLE001 - readiness must report any probe failure
            dependency = type(exc).__name__
            healthy = False

    if not healthy:
        response.status_code = 503

    return ReadinessResponse(
        status="UP" if healthy else "DOWN",
        service=SERVICE_NAME,
        version=SERVICE_VERSION,
        build=BUILD_SHA,
        python=PYTHON_VERSION,
        dependency=dependency,
    )
