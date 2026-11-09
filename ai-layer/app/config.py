"""Runtime metadata and dependency configuration for the AI layer."""

from __future__ import annotations

import os
import platform
from importlib.metadata import PackageNotFoundError, version

SERVICE_NAME = "ai-layer"


def package_version() -> str:
    """Return the installed package version, falling back to the pyproject version."""
    try:
        return version("awap-ai-layer")
    except PackageNotFoundError:
        return "0.1.0"


SERVICE_VERSION = os.getenv("AI_LAYER_VERSION", package_version())
BUILD_SHA = os.getenv("AI_LAYER_BUILD_SHA", "local")
PYTHON_VERSION = platform.python_version()

# Readiness is dependency-driven. The current layer has no mandatory runtime
# dependency, so readiness is healthy unless a dependency URL is configured.
DEPENDENCY_URL = os.getenv("AI_LAYER_DEPENDENCY_URL", "").strip() or None
DEPENDENCY_TIMEOUT_SECONDS = float(os.getenv("AI_LAYER_DEPENDENCY_TIMEOUT_SECONDS", "2.0"))
