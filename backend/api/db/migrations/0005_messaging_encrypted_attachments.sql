-- Encrypted message attachment payloads and per-device wrapped attachment keys.
-- The API stores ciphertext only; decryption metadata remains in the encrypted message body.
BEGIN;
SET LOCAL search_path = linka_private, public;

CREATE TABLE IF NOT EXISTS internal_message_attachments (
    attachment_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL
        REFERENCES conversations(conversation_id) ON DELETE RESTRICT,
    uploader_account_id UUID NOT NULL
        REFERENCES accounts(account_id) ON DELETE RESTRICT,
    message_id UUID UNIQUE
        REFERENCES internal_messages(message_id) ON DELETE RESTRICT,
    ciphertext BYTEA NOT NULL
        CHECK (octet_length(ciphertext) BETWEEN 28 AND 4194332),
    plaintext_size_bytes INTEGER NOT NULL
        CHECK (plaintext_size_bytes BETWEEN 1 AND 4194304),
    encryption_version TEXT NOT NULL
        CHECK (encryption_version = 'LINKA-E2EE-2'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS internal_message_attachments_conversation_idx
    ON internal_message_attachments (conversation_id, message_id, created_at);

CREATE TABLE IF NOT EXISTS internal_attachment_key_envelopes (
    attachment_id UUID NOT NULL
        REFERENCES internal_message_attachments(attachment_id) ON DELETE RESTRICT,
    recipient_device_id UUID NOT NULL,
    recipient_key_version INTEGER NOT NULL,
    ephemeral_public_key_der BYTEA NOT NULL
        CHECK (octet_length(ephemeral_public_key_der) BETWEEN 64 AND 512),
    hkdf_salt BYTEA NOT NULL CHECK (octet_length(hkdf_salt) = 16),
    wrap_nonce BYTEA NOT NULL CHECK (octet_length(wrap_nonce) = 12),
    wrapped_attachment_key BYTEA NOT NULL
        CHECK (octet_length(wrapped_attachment_key) = 48),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (attachment_id, recipient_device_id),
    FOREIGN KEY (recipient_device_id, recipient_key_version)
        REFERENCES device_encryption_keys(device_id, key_version) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS internal_attachment_key_envelopes_device_idx
    ON internal_attachment_key_envelopes (recipient_device_id, attachment_id);

INSERT INTO linka_private.schema_migrations(version, description)
VALUES (
    '0005_messaging_encrypted_attachments',
    'Store encrypted message attachment blobs and per-device wrapped attachment keys'
)
ON CONFLICT (version) DO NOTHING;

COMMIT;
