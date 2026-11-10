# Correlation IDs

Issue #191 defines one correlation-ID contract across HTTP, Kafka, the outbox, the AI layer and
structured logs.

## Contract

| Hop | Field | Rules |
|---|---|---|
| HTTP request/response | `X-Correlation-Id` header | Accepted from the client; generated as a UUID when missing or unsafe. Max 64 chars, `[A-Za-z0-9._:-]`, must start with a letter or digit. The response always echoes the resolved value. |
| Logs | `correlationId` MDC key | Emitted by the structured JSON encoder on every line. PII is masked before the encoder sees the message and the MDC values. |
| Outbox | `outbox_events.correlation_id` | Captured in the same transaction as the business change. Legacy rows may be `NULL`; the relay then sends no header and the consumer generates one. |
| Kafka | `X-Correlation-Id` record header | Added by the relay; restored into the consumer MDC by `CorrelationIdRecordInterceptor` and cleared after the record, including the failure path. |
| AI layer | `X-Correlation-Id` request and response header | The backend forwards the current ID; the Python middleware validates/generates it, logs `correlationId` and echoes it. The actuator health indicator fails when the echo is missing or different. |

## Code map

- [`CorrelationId`](../backend/common/src/main/java/com/awap/common/correlation/CorrelationId.java)
- [`CorrelationIdFilter`](../backend/common/src/main/java/com/awap/common/correlation/CorrelationIdFilter.java)
- [`PiiMasker`](../backend/common/src/main/java/com/awap/common/logging/PiiMasker.java)
- [`PiiMaskingConsoleAppender`](../backend/common/src/main/java/com/awap/common/logging/PiiMaskingConsoleAppender.java)
- [`CorrelationIdKafkaHeaders`](../backend/identity/src/main/java/com/awap/engine/infrastructure/CorrelationIdKafkaHeaders.java)
- [`CorrelationIdRecordInterceptor`](../backend/identity/src/main/java/com/awap/engine/infrastructure/CorrelationIdRecordInterceptor.java)
- [`AiLayerClient`](../backend/identity/src/main/java/com/awap/ai/infrastructure/AiLayerClient.java)
- [`app/observability.py`](../ai-layer/app/observability.py)

## PII masking policy

Masked: email local parts, bearer tokens, JSON/key-value secrets (`password`, `token`, `secret`,
`authorization`, `api_key`), Turkish national IDs and long opaque tokens (kept as a deterministic
`sha256:<8 hex>` prefix for correlation).

Kept readable: trace IDs, correlation IDs, UUIDs, aggregate IDs and HTTP metadata. Masking happens
in the appender, so a future logging call site cannot bypass it accidentally.

## Failure behaviour

- An unsafe or oversized client header is replaced and the request continues; the raw value is
  never logged.
- A missing Kafka header produces a generated ID rather than `null`.
- The MDC is cleared in `finally`/`afterRecord`/`failure`; a pooled thread never leaks an ID.
- An AI-layer probe that does not echo the ID makes the actuator health indicator `DOWN` when
  `awap.ai.layer.enabled=true`.
