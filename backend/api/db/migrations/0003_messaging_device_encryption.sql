-- Device-scoped ECDH key registry and per-message key envelopes.
-- The API stores public keys and wrapped content keys only; private keys and
-- message decryption remain on the member devices.
BEGIN;

CREATE TABLE IF NOT EXISTS device_encryption_keys (
    device_id UUID NOT NULL,
    account_id UUID NOT NULL,
    key_version INTEGER NOT NULL CHECK (key_version BETWEEN 1 AND 2147483647),
    key_algorithm TEXT NOT NULL CHECK (key_algorithm = 'ECDH-P256'),
    public_key_der BYTEA NOT NULL
        CHECK (octet_length(public_key_der) BETWEEN 64 AND 512),
    public_key_fingerprint CHAR(64) NOT NULL
        CHECK (public_key_fingerprint ~ '^[0-9A-F]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    retired_at TIMESTAMPTZ,
    PRIMARY KEY (device_id, key_version),
    FOREIGN KEY (device_id, account_id)
        REFERENCES user_devices(device_id, account_id) ON DELETE RESTRICT,
    CHECK (retired_at IS NULL OR retired_at >= created_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS device_encryption_keys_current_idx
    ON device_encryption_keys (device_id)
    WHERE retired_at IS NULL;

CREATE INDEX IF NOT EXISTS device_encryption_keys_account_idx
    ON device_encryption_keys (account_id, device_id, key_version DESC);

CREATE TABLE IF NOT EXISTS internal_message_key_envelopes (
    message_id UUID NOT NULL
        REFERENCES internal_messages(message_id) ON DELETE RESTRICT,
    recipient_device_id UUID NOT NULL,
    recipient_key_version INTEGER NOT NULL,
    ephemeral_public_key_der BYTEA NOT NULL
        CHECK (octet_length(ephemeral_public_key_der) BETWEEN 64 AND 512),
    hkdf_salt BYTEA NOT NULL CHECK (octet_length(hkdf_salt) = 16),
    wrap_nonce BYTEA NOT NULL CHECK (octet_length(wrap_nonce) = 12),
    wrapped_content_key BYTEA NOT NULL
        CHECK (octet_length(wrapped_content_key) = 48),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, recipient_device_id),
    FOREIGN KEY (recipient_device_id, recipient_key_version)
        REFERENCES device_encryption_keys(device_id, key_version) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS internal_message_key_envelopes_device_idx
    ON internal_message_key_envelopes (recipient_device_id, message_id);

INSERT INTO linka_private.schema_migrations(version, description)
VALUES (
    '0003_messaging_device_encryption',
    'Register versioned device ECDH public keys and persist per-device encrypted message-key envelopes'
)
ON CONFLICT (version) DO NOTHING;

COMMIT;
