-- Idempotency response replay columns.

ALTER TABLE idempotency_records ADD COLUMN response_status INTEGER NOT NULL DEFAULT 0;
ALTER TABLE idempotency_records ADD COLUMN response_body TEXT;
ALTER TABLE idempotency_records ADD COLUMN response_content_type VARCHAR(100);
