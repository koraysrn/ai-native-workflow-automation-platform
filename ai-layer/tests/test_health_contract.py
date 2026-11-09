"""Contract tests for the AI layer health endpoints (issue #185).

The OpenAPI document in `docs/openapi/ai-layer-openapi.yaml` is the versioned
contract. These tests fail when the runtime response, the documented schema or
the documented paths drift apart.
"""

from __future__ import annotations

from pathlib import Path

import httpx
import pytest
import yaml
from fastapi.testclient import TestClient

from app.api import health
from app.main import app

CONTRACT_PATH = Path(__file__).resolve().parents[2] / "docs" / "openapi" / "ai-layer-openapi.yaml"
CONTRACT = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
HEALTH_PATHS = {
    "/api/v1/health",
    "/api/v1/health/live",
    "/api/v1/health/ready",
}
client = TestClient(app)


def test_documented_health_paths_match_runtime_openapi() -> None:
    generated = set(client.get("/api/v1/openapi.json").json()["paths"])
    missing = HEALTH_PATHS - generated
    assert not missing, f"Documented health paths are not implemented: {sorted(missing)}"


def test_contract_version_matches_application_version() -> None:
    expected = CONTRACT["info"]["version"]
    actual = app.version
    assert actual == expected, f"version mismatch: expected {expected}, got {actual}"


def test_health_returns_the_documented_body() -> None:
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    body = response.json()
    documented = CONTRACT["components"]["schemas"]["HealthResponse"]["required"]
    assert set(body) == set(documented)
    assert body["status"] == "UP"
    assert body["service"] == "ai-layer"
    assert body["version"] == app.version
    assert body["python"].startswith("3.15")


def test_liveness_returns_the_same_contract() -> None:
    response = client.get("/api/v1/health/live")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_readiness_is_healthy_without_a_configured_dependency() -> None:
    response = client.get("/api/v1/health/ready")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["dependency"] == "none"
    documented = CONTRACT["components"]["schemas"]["ReadinessResponse"]["allOf"][1]["required"]
    assert set(documented) <= set(body)


def test_readiness_returns_503_when_a_dependency_is_down(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fail(_client: httpx.AsyncClient, _url: str, **_kwargs: object) -> httpx.Response:
        raise httpx.ConnectError("dependency down")

    monkeypatch.setattr(health, "DEPENDENCY_URL", "http://dependency.invalid/health")
    monkeypatch.setattr(health.httpx.AsyncClient, "get", fail)

    response = client.get("/api/v1/health/ready")
    assert response.status_code == 503
    body = response.json()
    assert body["status"] == "DOWN"
    assert body["dependency"] == "ConnectError"


def test_removed_documented_endpoint_is_detected() -> None:
    documented = set(CONTRACT["paths"])
    generated = set(client.get("/api/v1/openapi.json").json()["paths"])
    missing = sorted(documented - generated)
    assert documented <= generated, f"Documented but missing endpoints: {missing}"
