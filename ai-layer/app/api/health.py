"""Health check endpoints for the AWAP AI Layer."""

from fastapi import APIRouter

router = APIRouter(tags=["health"])


@router.get("/health")
def health() -> dict[str, str]:
    """Return the service health status."""
    return {"status": "UP", "service": "ai-layer"}
