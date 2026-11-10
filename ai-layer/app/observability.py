"""Correlation-ID propagation and JSON logging for the AI layer (issue #191).

The backend sends ``X-Correlation-Id`` on every call. This middleware validates the value, stores
it in a context variable so concurrent requests never mix IDs, echoes it on the response and emits
one JSON log line per request with the same field name the Java services use (``correlationId``).
"""

from __future__ import annotations

import contextvars
import json
import logging
import re
import uuid
from collections.abc import Awaitable, Callable

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

CORRELATION_HEADER = "X-Correlation-Id"
CORRELATION_LOG_FIELD = "correlationId"
MAX_LENGTH = 64
_SAFE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")

correlation_id_var: contextvars.ContextVar[str] = contextvars.ContextVar(
    "correlation_id", default=""
)
logger = logging.getLogger("awap.ai_layer")


def resolve(candidate: str | None) -> str:
    """Return the candidate when it is short and log-safe, otherwise a fresh UUID."""
    if candidate is None:
        return str(uuid.uuid4())
    trimmed = candidate.strip()
    if len(trimmed) > MAX_LENGTH or not _SAFE.match(trimmed):
        return str(uuid.uuid4())
    return trimmed


def current_correlation_id() -> str:
    return correlation_id_var.get()


class JsonFormatter(logging.Formatter):
    """Minimal JSON formatter that always includes the active correlation ID."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "correlationId": current_correlation_id(),
        }
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str)


def configure_logging() -> None:
    """Install the JSON formatter once on the root logger."""
    root = logging.getLogger()
    if any(isinstance(handler.formatter, JsonFormatter) for handler in root.handlers):
        return
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root.addHandler(handler)
    root.setLevel(logging.INFO)


class CorrelationIdMiddleware(BaseHTTPMiddleware):
    """Validates/creates the correlation ID, exposes it in logs and echoes it back."""

    async def dispatch(
        self,
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        candidate = request.headers.get(CORRELATION_HEADER)
        correlation_id = resolve(candidate)
        token = correlation_id_var.set(correlation_id)
        try:
            response = await call_next(request)
            response.headers[CORRELATION_HEADER] = correlation_id
            logger.info("HTTP %s %s -> %s", request.method, request.url.path, response.status_code)
            return response
        finally:
            correlation_id_var.reset(token)
