-- V53 (#204): provider message id on the delivery row.
--
-- A real provider (SMTP, SMS gateway, FCM/APNs) returns an identifier for every accepted message.
-- Storing it lets support correlate a delivery row with the provider's own event log (bounces,
-- complaints, delivery receipts) and lets the DLQ replay tool prove that a resend would duplicate
-- an already accepted message. The column is nullable: rows written before this migration and
-- channels without a provider id (web, webhook, disabled transports) keep NULL.

ALTER TABLE notification_deliveries
    ADD COLUMN provider_message_id VARCHAR(255);

COMMENT ON COLUMN notification_deliveries.provider_message_id IS
    'Provider-assigned message id returned by the transport after dispatch (issue #204).';
