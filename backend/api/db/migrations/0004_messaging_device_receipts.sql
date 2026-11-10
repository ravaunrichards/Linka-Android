-- Per-device delivery and read receipt state for E2EE messages.
-- Receipt rows are created only for authenticated active devices that received
-- a per-device content-key envelope.
BEGIN;
SET LOCAL search_path = linka_private, public;

CREATE TABLE IF NOT EXISTS internal_message_device_receipts (
    message_id UUID NOT NULL
        REFERENCES internal_messages(message_id) ON DELETE RESTRICT,
    recipient_device_id UUID NOT NULL,
    recipient_account_id UUID NOT NULL,
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    PRIMARY KEY (message_id, recipient_device_id),
    FOREIGN KEY (recipient_device_id, recipient_account_id)
        REFERENCES user_devices(device_id, account_id) ON DELETE RESTRICT,
    CHECK (read_at IS NULL OR delivered_at IS NOT NULL),
    CHECK (read_at IS NULL OR read_at >= delivered_at)
);

CREATE INDEX IF NOT EXISTS internal_message_device_receipts_account_idx
    ON internal_message_device_receipts (recipient_account_id, recipient_device_id, message_id);

INSERT INTO linka_private.schema_migrations(version, description)
VALUES (
    '0004_messaging_device_receipts',
    'Persist per-device delivery and read acknowledgement times for encrypted internal messages'
)
ON CONFLICT (version) DO NOTHING;

COMMIT;
