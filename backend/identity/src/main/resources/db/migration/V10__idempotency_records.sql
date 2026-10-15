-- Idempotency support for mutating endpoints.

CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_records UNIQUE (tenant_id, idempotency_key)
);
