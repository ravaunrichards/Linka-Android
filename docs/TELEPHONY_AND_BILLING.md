# LINKA telephony, numbers, free service and credits

## 1. Separate commercial services

LINKA must display three independent service categories:
1. Number service — acquiring, renting, renewing, porting or releasing a telephone number.
2. Voice service — calling a LINKA user or ordinary telephone number.
3. Messaging service — internal messaging or external SMS/MMS.

A selected telephone number does not define the price of a call made to that number. Number lifecycle charges and call usage charges remain separate.

## 2. Number lifecycle

The number service must search provider-confirmed inventory, identify country/area code/number type/features, and clearly show whether a number is free, trial, promotional, one-time purchase or recurring rental.

Show recurring price, billing period, renewal date, taxes, restrictions and cancellation terms before confirmation. Verify eligibility and ownership where required. Handle purchase/rental, renewal, cancellation and release with provider confirmation. Support inbound voice or messaging only when the selected number/provider supports it.

Initial product scope includes Jamaica (+1 876 and +1 658), the US, Canada, UK and additional markets as provider coverage expands. Country code +1 alone is insufficient to identify destination market; use validated number metadata.

## 3. Free calling model

### Internal LINKA calls
- LINKA-to-LINKA internet audio calling is free to users.
- LINKA-to-LINKA video calling is intended to be free after implementation.
- No internal call or message deducts purchased wallet credits.

### External free calls
Free external calling may be funded through carrier/wholesale termination agreements, LINKA-funded minute allowances, sponsorship, disclosed advertising allowances where lawful, or subscription/business plans.

The eligibility service must know destination, route, allowance balance, schedule and sponsorship rules. Show remaining allowance when useful. Do not show a free offer unless the route and funding actually support it.

## 4. Credit calling model

Credits are optional and apply only to displayed paid services, such as external voice calls and eligible external messages.

For every paid option, show destination/number type, a clear route description, price per billing unit, minimum charge and rounding unit, applicable taxes/fees, estimated costs and spend controls.

Do not publish final LINKA credit prices until provider tariffs, exchange costs, taxes, payment fees and target-market economics have been verified.

## 5. User choice and route policy

1. Validate and normalize the full destination number.
2. Determine available internal, internet, nearby or carrier paths.
3. For ordinary telephone numbers, check provider reachability and route eligibility.
4. Check funded free allowance.
5. Display genuine free and credit choices.
6. Respect user connection preferences.
7. Obtain consent before an external paid action.
8. Start only the selected route.
9. If the free route fails, stop and ask before trying a paid route.
10. Record route, consent, rate version and provider result.

Automatic switching is allowed only among supported and authorized paths. It must never create a surprise charge.

## 6. Wallet ledger

The backend must be authoritative. Suggested ledger event types:
- PURCHASE_PENDING
- PURCHASE_CONFIRMED
- PROMOTIONAL_GRANT
- REFERRAL_GRANT
- SPONSORED_GRANT
- CALL_RESERVATION
- CALL_USAGE
- MESSAGE_USAGE
- NUMBER_RENTAL
- NUMBER_RENEWAL
- REFUND
- REVERSAL
- EXPIRY, only if published terms permit expiration

Each event should include a unique idempotency key, account reference, amount, credit source, currency/credit unit, event time, related call/message/number, provider reference, rate version and status. Minimize sensitive provider data.

Do not update a balance based solely on a client request. Validate available credits and write ledger events atomically. Authenticate and deduplicate payment webhooks.

## 7. Current repository implementation

Provider-independent API domain modules now exist under `backend/api/src/domain/`:

- `route-options.mjs` offers only provider-confirmed free and credit routes, requires exact-quote consent for paid actions and limits optional paid auto-fallback to a matching preauthorization and spend cap.
- `e164.mjs` normalizes explicit international `+` and `00` telephone formats. It does not infer country or number type.
- `rate-catalog.mjs` validates provider-supplied tariffs, selects the most specific active provider/destination/number-type rate and calculates credit estimates for voice, SMS and MMS.
- `credit-ledger.mjs` applies immutable integer-credit events to separate purchased, promotional and sponsored buckets, with idempotency, balance, usage and refund checks.

The latest LINKA API CI run recorded 49 passing tests and zero failures for these domains plus the existing API runtime tests.

These are tested domain foundations, not a live billing service. They are not yet connected to provider tariff feeds, number inventory, persistent storage, authenticated billing endpoints, payment processing, Android or website rate/wallet screens. The in-memory ledger is not a production source of truth across concurrent server instances or restarts. See [Wallet ledger design](WALLET_LEDGER_DESIGN.md).

## 8. Billing states and failed calls

Define a lifecycle: preflight, rate quote, user consent, route setup, ringing, answered/connected, completed or failed, final usage reconciliation, refund/reversal when required.

A failed connection, rejected call or unconnected attempt must not be billed as a completed conversation. Credit reservation and release behavior must be documented and tested.

## 9. Payments and refunds

Evaluate card, PayPal and market-appropriate payment providers after confirming availability and compliance. Checkout discloses currency, amount charged, credit quantity, taxes, exchange rate where relevant and refund policy.

Never store raw card credentials. Keep production payment keys outside the repository. Verify webhook signatures, event identity and idempotency. Reconcile settlements against purchases and wallet grants.

## 10. Rate catalog

A rate record should include destination country and normalized prefix/number metadata, number type where known, provider/route ID, billing unit and rounding rule, customer price/currency, applicable tax/fee model, provider tariff reference, effective dates, source/import time/version, direction and restrictions.

Do not use stale rates after expiry. If a current rate cannot be loaded, do not invent a quote; report the service unavailable or use a clearly labeled still-valid cached rate under policy.

## 11. Competitor reference principles

TextNow, 2ndLine, TextMe, Dingtone and Talkatone were reviewed as examples of balances, credit packages and destination rates. Their rates and credits are not interchangeable with LINKA currency or provider costs. They are design references, never LINKA pricing evidence.
