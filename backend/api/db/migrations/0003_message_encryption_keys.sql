-- Bind one current P-256 message-encryption public key to each authenticated device.
-- Retired keys remain stored so existing encrypted message envelopes can still be opened
-- by devices that retain their corresponding non-exportable private key.
BEGIN;

CREATE TABLE IF NOT EXISTS linka_private.message_encryption_keys (
    key_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    device_id UUID NOT NULL,
    public_key_der BYTEA NOT NULL
        CHECK (octet_length(public_key_der) BETWEEN 64 AND 512),
    key_fingerprint CHAR(64) NOT NULL
        CHECK (key_fingerprint ~ '^[0-9A-F]{64}$'),
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    retired_at TIMESTAMPTZ,
    FOREIGN KEY (device_id, account_id)
        REFERENCES linka_private.user_devices(device_id, account_id)
        ON DELETE RESTRICT,
    CHECK ((is_current AND retired_at IS NULL) OR (NOT is_current AND retired_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS message_encryption_keys_one_current_per_device_idx
    ON linka_private.message_encryption_keys (device_id)
    WHERE is_current = TRUE;

CREATE INDEX IF NOT EXISTS message_encryption_keys_account_current_idx
    ON linka_private.message_encryption_keys (account_id, created_at DESC)
    WHERE is_current = TRUE;

INSERT INTO linka_private.schema_migrations(version, description)
VALUES (
    '0003_message_encryption_keys',
    'Bind P-256 message-encryption public keys to authenticated device signing identities and retain retired keys'
)
ON CONFLICT (version) DO NOTHING;

COMMIT;
