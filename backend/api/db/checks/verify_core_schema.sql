-- Executed against the PostgreSQL service by database-ci.yml.
-- All test records live inside a transaction and are rolled back.
BEGIN;

DO $checks$
DECLARE
    required_table TEXT;
BEGIN
    FOREACH required_table IN ARRAY ARRAY[
        'linka_private.accounts',
        'linka_private.user_devices',
        'linka_private.auth_sessions',
        'linka_private.telephony_providers',
        'linka_private.telephone_numbers',
        'linka_private.provider_rates',
        'linka_private.wallet_accounts',
        'linka_private.wallet_ledger_events',
        'linka_private.payment_transactions',
        'linka_private.provider_webhook_events',
        'linka_private.call_sessions',
        'linka_private.conversations',
        'linka_private.conversation_members',
        'linka_private.internal_messages',
        'linka_private.external_message_jobs'
    ] LOOP
        IF to_regclass(required_table) IS NULL THEN
            RAISE EXCEPTION 'missing_required_table:%', required_table;
        END IF;
    END LOOP;

    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'wallet_ledger_append_only'
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'wallet_ledger_append_only_trigger_missing';
    END IF;

    IF to_regprocedure('linka_private.apply_wallet_event(text,text,uuid,text,text,bigint,text,text)') IS NULL THEN
        RAISE EXCEPTION 'apply_wallet_event_function_missing';
    END IF;
END;
$checks$;

INSERT INTO linka_private.accounts (account_id, email_normalized, display_name)
VALUES
    ('00000000-0000-4000-8000-000000000001', 'schema-test@example.invalid', 'Schema Test'),
    ('00000000-0000-4000-8000-000000000007', 'schema-test-2@example.invalid', 'Schema Test 2');

INSERT INTO linka_private.user_devices (
    device_id, account_id, device_fingerprint, signing_public_key_der, platform
) VALUES (
    '00000000-0000-4000-8000-000000000002',
    '00000000-0000-4000-8000-000000000001',
    repeat('A', 64),
    decode(repeat('AB', 64), 'hex'),
    'android'
);

INSERT INTO linka_private.auth_sessions (
    session_id, account_id, device_id, token_hash, expires_at
) VALUES (
    '00000000-0000-4000-8000-000000000003',
    '00000000-0000-4000-8000-000000000001',
    '00000000-0000-4000-8000-000000000002',
    decode(repeat('AB', 32), 'hex'),
    now() + interval '1 hour'
);

INSERT INTO linka_private.telephony_providers (provider_id, display_name, provider_kind)
VALUES
    ('ci-number-provider', 'CI Number Provider', 'numbering'),
    ('ci-voice-provider', 'CI Voice Provider', 'voice'),
    ('ci-message-provider', 'CI Message Provider', 'messaging'),
    ('ci-payment-provider', 'CI Payment Provider', 'payment');

INSERT INTO linka_private.telephone_numbers (
    number_id, provider_id, provider_number_ref, e164, country_iso2,
    number_type, lifecycle_status, assigned_account_id, supports_voice, supports_sms
) VALUES (
    '00000000-0000-4000-8000-000000000004',
    'ci-number-provider',
    'ci-number-001',
    '+18765550100',
    'JM',
    'mobile',
    'active',
    '00000000-0000-4000-8000-000000000001',
    TRUE,
    TRUE
);

INSERT INTO linka_private.provider_rates (
    rate_id, provider_id, route_id, provider_tariff_ref, rate_version,
    service, destination_country_iso2, destination_prefix, number_type,
    billing_unit, billing_increment_units, minimum_billable_units,
    credit_units_per_increment, effective_from
) VALUES (
    'ci-jm-mobile-voice-v1',
    'ci-voice-provider',
    'ci-route-jm',
    'ci-tariff-jm',
    'v1',
    'voice',
    'JM',
    '+1876',
    'mobile',
    'minute',
    1,
    1,
    12,
    now() - interval '1 day'
);

INSERT INTO linka_private.wallet_accounts (wallet_id, account_id)
VALUES
    ('00000000-0000-4000-8000-000000000005', '00000000-0000-4000-8000-000000000001'),
    ('00000000-0000-4000-8000-000000000008', '00000000-0000-4000-8000-000000000007');

INSERT INTO linka_private.conversations (
    conversation_id, conversation_kind, created_by_account_id
) VALUES (
    '00000000-0000-4000-8000-000000000009',
    'direct',
    '00000000-0000-4000-8000-000000000001'
);

INSERT INTO linka_private.conversation_members (conversation_id, account_id)
VALUES (
    '00000000-0000-4000-8000-000000000009',
    '00000000-0000-4000-8000-000000000001'
);

DO $wallet_tests$
DECLARE
    result RECORD;
    caught BOOLEAN;
BEGIN
    SELECT * INTO result
    FROM linka_private.apply_wallet_event(
        'purchase-1', 'purchase-key-1',
        '00000000-0000-4000-8000-000000000001',
        'PURCHASE_CONFIRMED', 'purchased', 100,
        'payment-ci-1', NULL
    );
    IF result.event_status <> 'applied' OR result.purchased_credit_units <> 100 THEN
        RAISE EXCEPTION 'wallet_purchase_failed';
    END IF;

    SELECT * INTO result
    FROM linka_private.apply_wallet_event(
        'purchase-1', 'purchase-key-1',
        '00000000-0000-4000-8000-000000000001',
        'PURCHASE_CONFIRMED', 'purchased', 100,
        'payment-ci-1', NULL
    );
    IF result.event_status <> 'duplicate' OR result.purchased_credit_units <> 100 THEN
        RAISE EXCEPTION 'wallet_idempotency_failed';
    END IF;

    SELECT * INTO result
    FROM linka_private.apply_wallet_event(
        'call-usage-1', 'call-usage-key-1',
        '00000000-0000-4000-8000-000000000001',
        'CALL_USAGE', 'purchased', 30,
        'call-ci-1', NULL
    );
    IF result.event_status <> 'applied' OR result.purchased_credit_units <> 70 THEN
        RAISE EXCEPTION 'wallet_usage_failed';
    END IF;

    SELECT * INTO result
    FROM linka_private.apply_wallet_event(
        'refund-1', 'refund-key-1',
        '00000000-0000-4000-8000-000000000001',
        'REFUND', 'purchased', 10,
        'refund-ci-1', 'call-usage-1'
    );
    IF result.event_status <> 'applied' OR result.purchased_credit_units <> 80 THEN
        RAISE EXCEPTION 'wallet_refund_failed';
    END IF;

    caught := FALSE;
    BEGIN
        PERFORM *
        FROM linka_private.apply_wallet_event(
            'overspend-1', 'overspend-key-1',
            '00000000-0000-4000-8000-000000000001',
            'CALL_USAGE', 'purchased', 1000,
            'call-ci-overspend', NULL
        );
    EXCEPTION WHEN SQLSTATE 'P0001' THEN
        IF SQLERRM <> 'insufficient_credits' THEN RAISE; END IF;
        caught := TRUE;
    END;
    IF NOT caught THEN RAISE EXCEPTION 'wallet_overspend_was_not_blocked'; END IF;

    caught := FALSE;
    BEGIN
        UPDATE linka_private.wallet_ledger_events
        SET amount_credit_units = 1
        WHERE event_id = 'purchase-1';
    EXCEPTION WHEN SQLSTATE 'P0001' THEN
        IF SQLERRM <> 'wallet_ledger_is_append_only' THEN RAISE; END IF;
        caught := TRUE;
    END;
    IF NOT caught THEN RAISE EXCEPTION 'wallet_ledger_mutation_was_not_blocked'; END IF;
END;
$wallet_tests$;

DO $cross_account_tests$
DECLARE
    caught BOOLEAN;
BEGIN
    caught := FALSE;
    BEGIN
        INSERT INTO linka_private.wallet_ledger_events (
            event_id, wallet_id, account_id, idempotency_key,
            event_type, credit_source, amount_credit_units
        ) VALUES (
            'cross-wallet-event',
            '00000000-0000-4000-8000-000000000005',
            '00000000-0000-4000-8000-000000000007',
            'cross-wallet-idem',
            'PURCHASE_CONFIRMED',
            'purchased',
            1
        );
    EXCEPTION WHEN foreign_key_violation THEN
        caught := TRUE;
    END;
    IF NOT caught THEN RAISE EXCEPTION 'cross_account_wallet_event_was_not_blocked'; END IF;

    caught := FALSE;
    BEGIN
        INSERT INTO linka_private.call_sessions (
            call_id, account_id, device_id, call_mode, call_status
        ) VALUES (
            '00000000-0000-4000-8000-000000000010',
            '00000000-0000-4000-8000-000000000007',
            '00000000-0000-4000-8000-000000000002',
            'internal',
            'initiated'
        );
    EXCEPTION WHEN foreign_key_violation THEN
        caught := TRUE;
    END;
    IF NOT caught THEN RAISE EXCEPTION 'cross_account_call_device_was_not_blocked'; END IF;

    caught := FALSE;
    BEGIN
        INSERT INTO linka_private.internal_messages (
            message_id, conversation_id, sender_account_id, ciphertext, encryption_version
        ) VALUES (
            '00000000-0000-4000-8000-000000000011',
            '00000000-0000-4000-8000-000000000009',
            '00000000-0000-4000-8000-000000000007',
            decode('01', 'hex'),
            'ci-v1'
        );
    EXCEPTION WHEN foreign_key_violation THEN
        caught := TRUE;
    END;
    IF NOT caught THEN RAISE EXCEPTION 'non_member_message_sender_was_not_blocked'; END IF;

    caught := FALSE;
    BEGIN
        INSERT INTO linka_private.external_message_jobs (
            message_job_id, account_id, provider_id, sender_number_id,
            destination_e164, message_type, delivery_status
        ) VALUES (
            '00000000-0000-4000-8000-000000000012',
            '00000000-0000-4000-8000-000000000007',
            'ci-message-provider',
            '00000000-0000-4000-8000-000000000004',
            '+18765550199',
            'sms',
            'queued'
        );
    EXCEPTION WHEN foreign_key_violation THEN
        caught := TRUE;
    END;
    IF NOT caught THEN RAISE EXCEPTION 'other_account_sender_number_was_not_blocked'; END IF;
END;
$cross_account_tests$;

DO $uniqueness_tests$
DECLARE
    caught BOOLEAN := FALSE;
BEGIN
    BEGIN
        INSERT INTO linka_private.telephone_numbers (
            number_id, provider_id, provider_number_ref, e164, country_iso2,
            number_type, lifecycle_status, assigned_account_id
        ) VALUES (
            '00000000-0000-4000-8000-000000000006',
            'ci-number-provider',
            'ci-number-duplicate',
            '+18765550100',
            'JM',
            'mobile',
            'active',
            '00000000-0000-4000-8000-000000000001'
        );
    EXCEPTION WHEN unique_violation THEN
        caught := TRUE;
    END;
    IF NOT caught THEN RAISE EXCEPTION 'duplicate_active_phone_number_was_not_blocked'; END IF;
END;
$uniqueness_tests$;

ROLLBACK;
