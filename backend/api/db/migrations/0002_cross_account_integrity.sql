-- Cross-account integrity constraints. Add new constraints in a forward migration;
-- do not edit 0001_core_schema.sql after it has been applied.
BEGIN;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'linka_private.wallet_accounts'::regclass
          AND conname = 'wallet_accounts_wallet_account_unique'
    ) THEN
        ALTER TABLE linka_private.wallet_accounts
            ADD CONSTRAINT wallet_accounts_wallet_account_unique
            UNIQUE (wallet_id, account_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'linka_private.wallet_ledger_events'::regclass
          AND conname = 'wallet_ledger_wallet_account_fk'
    ) THEN
        ALTER TABLE linka_private.wallet_ledger_events
            ADD CONSTRAINT wallet_ledger_wallet_account_fk
            FOREIGN KEY (wallet_id, account_id)
            REFERENCES linka_private.wallet_accounts(wallet_id, account_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'linka_private.telephone_numbers'::regclass
          AND conname = 'telephone_numbers_id_account_unique'
    ) THEN
        ALTER TABLE linka_private.telephone_numbers
            ADD CONSTRAINT telephone_numbers_id_account_unique
            UNIQUE (number_id, assigned_account_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'linka_private.call_sessions'::regclass
          AND conname = 'call_sessions_device_account_fk'
    ) THEN
        ALTER TABLE linka_private.call_sessions
            ADD CONSTRAINT call_sessions_device_account_fk
            FOREIGN KEY (device_id, account_id)
            REFERENCES linka_private.user_devices(device_id, account_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'linka_private.internal_messages'::regclass
          AND conname = 'internal_messages_sender_membership_fk'
    ) THEN
        ALTER TABLE linka_private.internal_messages
            ADD CONSTRAINT internal_messages_sender_membership_fk
            FOREIGN KEY (conversation_id, sender_account_id)
            REFERENCES linka_private.conversation_members(conversation_id, account_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'linka_private.external_message_jobs'::regclass
          AND conname = 'external_message_sender_number_account_fk'
    ) THEN
        ALTER TABLE linka_private.external_message_jobs
            ADD CONSTRAINT external_message_sender_number_account_fk
            FOREIGN KEY (sender_number_id, account_id)
            REFERENCES linka_private.telephone_numbers(number_id, assigned_account_id)
            ON DELETE RESTRICT;
    END IF;
END;
$migration$;

INSERT INTO linka_private.schema_migrations(version, description)
VALUES (
    '0002_cross_account_integrity',
    'Bind wallet events, call devices, conversation senders and outbound numbers to their owning accounts'
)
ON CONFLICT (version) DO NOTHING;

COMMIT;
