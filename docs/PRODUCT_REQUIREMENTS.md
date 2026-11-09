# LINKA product requirements

## 1. Product definition

LINKA is an independent international telephone and communication platform. Its target product combines a native Android telephone experience, a full-featured website, virtual-number services, internet calling, carrier connectivity where contracted, messaging, optional credits and nearby offline voice communication.

Reference products for research include TextNow, 2ndLine, TextMe, Dingtone and Talkatone. LINKA must use its own provider-backed product and pricing rather than copying another app's prices or implying that another provider's rates apply to LINKA.

**Product promise:** LINKA — Your phone. Your number. Your choice. Free when available. Credits when needed. Connected wherever supported.

This is the target product scope, not a statement that every capability is already implemented.

## 2. Product principles

1. One independent LINKA product and one authoritative GitHub repository.
2. Jamaican and international telephone-number support, subject to real provider coverage.
3. Both free and credit choices wherever a genuine route or allowance exists.
4. Unlimited LINKA-to-LINKA audio calls and messages without credit deductions.
5. External free calling only when the carrier cost is funded or covered by a valid agreement.
6. No surprise charges, hidden deductions, mandatory top-ups or silent conversion from a free route to a paid route.
7. Separate number rental, call usage and messaging charges.
8. Connection switching only among supported paths and in accordance with user consent and settings.
9. Nearby offline voice is separate from ordinary PSTN telephone service.
10. Only source-backed, tested functionality may be described as working.

## 3. Countries and number support

Initial number-market requirements:
- Jamaica: +1 876 and +1 658.
- United States: +1 numbers that are actually provisionable for the selected destination and number type.
- Canada: +1 numbers that are actually provisionable for the selected destination and number type.
- United Kingdom: +44 numbers where available.
- Additional international destinations as provider coverage and regulatory eligibility permit.

The North American Numbering Plan country code +1 is shared across multiple countries and territories. LINKA must distinguish Jamaica, the United States, Canada and other supported destinations using the actual number and area-code allocation rather than treating every +1 number as interchangeable.

Number capabilities:
- Search by country, region and available area code.
- Display only provider-confirmed available numbers.
- Show number type and eligibility restrictions.
- Label free number offers only when the provider actually offers them.
- Acquire, rent, renew, release and manage numbers.
- Support multiple numbers per user where provider rules permit.
- Receive inbound calls and supported messages.
- Select an authorized outbound caller ID.
- Verify number ownership where required.
- Support number porting only when the provider and destination rules permit.
- Display recurring charges, taxes, renewal date and cancellation consequences before confirmation.

Number rental or acquisition is separate from call and messaging usage charges.

## 4. Free and credit service model

### 4.1 Free internal LINKA services
- Unlimited LINKA-to-LINKA audio calling over a supported internet path.
- Free LINKA-to-LINKA video calling once implemented.
- Free LINKA-to-LINKA messaging.
- No wallet deduction for eligible internal voice or messaging traffic.

Abuse controls and fair-use protections may protect platform availability, but must not be disguised credit charges.

### 4.2 Free external services

Calls or messages to ordinary telephone numbers may be offered free to the user when the cost is covered by a carrier/wholesale agreement, LINKA-funded allowance, sponsor-funded allowance, disclosed advertising-funded allowance or a subscription/business plan that funds the service.

The eligibility service must know the destination, route, allowance balance, schedule and sponsorship rules. Show the eligibility and limits clearly.

Do not promise universal unlimited free PSTN calling without confirmed funding and provider coverage.

### 4.3 Credit services

Optional credits may be used for:
- Calls to external Jamaican numbers.
- Calls to supported US, Canadian, UK and other international numbers.
- Eligible external SMS and MMS.
- Number acquisition, rental and renewal if the commercial model uses credits.
- Clearly identified premium communication features.

The product may also use direct currency prices for number rental or subscriptions. Users must distinguish credit deductions from direct payments.

### 4.4 Free-first and consent flow

1. Normalize and validate the entered number.
2. Determine destination and number type where reliable metadata exists.
3. Check service/provider availability.
4. Check whether an eligible free route or allowance exists.
5. Display the free option and restrictions.
6. Display the credit option and rate if supported.
7. Let the user choose.
8. Obtain explicit authorization before any charge.
9. Start only the selected route.
10. If the free route fails, do not silently switch to a billable route; ask before using credits.

Incoming calls must not be described as free unless provider and account terms confirm this.

## 5. Wallet and credit requirements

The optional wallet must support:
- Current available balance.
- Purchased-credit and promotional-credit balances tracked separately.
- Credit package purchases and custom amounts where supported.
- Promotional, referral and sponsor credits.
- Transaction and receipt history.
- Per-call and per-message usage history.
- Refund and reversal records.
- Spending limits and alerts.
- Optional automatic top-up with explicit opt-in, configurable amount and cancellation.
- Published credit-expiry and promotional-credit rules, if expiry applies.
- Support/dispute references for transactions.

Ledger requirements:
- The server is authoritative for balances.
- Every grant, purchase, deduction, refund and reversal is an immutable ledger event.
- Ledger writes are atomic and idempotent.
- Provider webhooks are signature-verified and deduplicated.
- A client must never set its own balance.
- Unauthorized negative balances are not permitted.
- Failed, rejected or unconnected calls are not billed as completed calls.
- Define answer, disconnect, rounding and minimum-billable-duration rules before charging.
- Store the applied rate version and provider cost for every billable event.
- Reconcile provider settlements against wallet credits and carrier usage.
- Separate purchased, promotional and sponsored credit sources in reporting.

Payment options to evaluate when available:
- Debit and credit cards.
- PayPal.
- Other providers suitable for supported markets.
- Locally appropriate payment methods where available.

Do not put payment secrets or carrier credentials in source control.

## 6. Destination rate finder

Users must be able to:
- Search by country, calling code or complete telephone number.
- View mobile, landline and other provider-supported number categories separately.
- See whether the number can be reached by LINKA.
- See free eligibility and allowance limits.
- See the credit price and billing unit.
- See SMS and MMS prices separately.
- See number rental and renewal charges separately.
- Estimate 1, 5, 10, 30 and 60 minute costs.
- Review taxes, rounding rules, minimum charges and restrictions.

Rates must be sourced from current provider rate data, have effective dates/versions and be refreshed according to provider terms. Do not hardcode competitor rates as LINKA prices. Do not describe a rate as live if it has not been verified.

## 7. Voice calling features

Target features:
- Incoming and outgoing calls.
- Call waiting, hold and resume.
- Mute and speakerphone.
- Bluetooth and wired headset routing where supported.
- Caller ID and authorized outbound number selection.
- Call history and missed calls.
- Contacts, favorites and speed dial.
- Conference calls.
- Call forwarding and transfer where provider-supported.
- Voicemail and visual voicemail where provider-supported.
- Voicemail notifications and transcription where supported.
- Spam detection and call blocking.
- Do Not Disturb and user-defined call settings.
- Multiple-number management.

Call recording is optional and must not be introduced without consent controls, appropriate disclosure, secure storage and legal review for relevant jurisdictions.

## 8. Video and internet calling

- LINKA-to-LINKA audio calls over supported internet paths.
- LINKA-to-LINKA video calls.
- Secure call setup and signaling.
- WebRTC media transport.
- TURN support for restrictive network conditions where required.
- Call controls and audio-route management.
- Network quality monitoring and reconnection.
- Clear distinction between signaling availability and actual media connectivity.

The existing backend signaling API does not itself relay voice audio and does not prove internet calling is integrated in Android or the browser client.

## 9. Messaging

Target capabilities:
- Internal LINKA instant messages.
- External SMS and MMS.
- One-to-one and group conversations where provider support permits.
- Pictures, video attachments and voice notes where the chosen messaging path supports them.
- Delivery status and read receipts where available.
- Conversation history and search.
- Attachment management and push notifications.
- Spam controls and blocked contacts.
- Offline queuing and later delivery where appropriate.
- Cross-device synchronization.

Clearly distinguish free internal messages from provider-billed external SMS/MMS. Display a charge before sending paid external messages.

## 10. Connectivity and automatic switching

Connection categories to evaluate:
- Wi-Fi internet.
- Mobile-data internet.
- Cellular/SIM telephone service.
- eSIM-based cellular service, only with provider provisioning and device support.
- Nearby Bluetooth or other supported local-radio communication.
- Nearby Wi-Fi-based communication.
- Optional offline mesh relay communication.

Automatic switching must detect only genuinely available and supported paths, respect user preferences, prefer eligible free or already-authorized paths, show route and expected price, and never switch to a paid carrier route without user authorization.

A nearby mesh path cannot reach an ordinary telephone number without a gateway connected to the telephone network.

SIM/eSIM support is an integration and commercial dependency, not merely an application permission.

## 11. Android application

Target a complete native Android experience using Kotlin and Jetpack Compose:
- Registration, login and account recovery.
- Main telephone dashboard and dial pad.
- Contacts, favorites and recent calls.
- Incoming-call and active-call screens.
- Call controls.
- Messaging inbox and conversations.
- Number search, acquisition and management.
- Wallet, credit packages and purchase history.
- Destination rate finder.
- Voicemail and notifications.
- Device/session management.
- Privacy/security settings.
- Nearby/offline calling controls.

Integrate Android Telecom and default-dialer functionality only after reviewing current platform requirements and role implications. Handle permissions, foreground services, audio focus, Bluetooth, background operation and OS lifecycle restrictions safely.

## 12. Website

The full website must eventually provide an account-based product:
- Registration, login and recovery.
- Account dashboard.
- Number search and management.
- Supported browser dialing.
- Browser audio/video calls.
- Messaging.
- Contacts and call history.
- Wallet and purchase flow.
- Rate finder.
- Payment/transaction history.
- Account, security and privacy settings.
- Responsive desktop and mobile layouts.

A standard browser cannot directly use a handset SIM as a native Android app can. Clearly disclose platform differences.

## 13. Backend and data

The final backend architecture must provide:
- Authentication and account identity.
- User/device/session management.
- Number provisioning adapters.
- Carrier/PSTN/SIP adapters.
- Internet call signaling and media integration.
- SMS/MMS adapters.
- Number inventory and rental lifecycle.
- Durable destination rate catalog.
- Wallet and immutable ledger.
- Payment integration and webhook handling.
- Call/message usage records.
- Notifications.
- Abuse/fraud/spam controls.
- Audit records.
- Monitoring, backup and recovery.

PostgreSQL is the preferred durable relational store for account, number, rate, usage and wallet data. The current in-memory signaling runtime is not a replacement for durable user, number, payment or wallet storage.

## 14. Privacy, safety and regulation

- Review Jamaican telecommunications requirements and provider obligations.
- Review destination-market telecom and number rules before enabling international service.
- Verify identity/number ownership where required.
- Provide privacy policy, terms, data access/export/deletion controls.
- Minimize personal data and protect it in transit and at rest.
- Establish consent and retention rules for call/message data.
- Implement anti-spam and anti-fraud controls.
- Protect billing records and provider credentials.
- Disclose service limitations clearly.
- Do not claim emergency calling until implemented, tested and approved in the relevant markets.
- LINKA offline mode is not an emergency telephone service.

## 15. Product acceptance criteria

A product area is complete only when real behavior is verified:
- Number selection returns provider-confirmed inventory and rejects unavailable numbers.
- Supported destinations display a current rate and service eligibility.
- Free and credit paths match actual route availability.
- Internal LINKA calls/messages do not deduct credits.
- A paid route cannot start without explicit authorization.
- A failed free path does not automatically trigger paid fallback.
- Wallet balance is server-authoritative and reconciles against ledger events.
- Payment webhooks are authenticated and idempotent.
- Carrier calls reach ordinary destinations through a contracted provider.
- External messages are actually delivered through a provider.
- Internet audio/video calls complete on tested Android devices and supported browsers.
- Connection switching respects user settings and billing consent.
- Android permissions and background-call behavior pass device tests.
- Offline direct and relay calls pass documented physical-device tests.
- Security and regulatory reviews are completed before production claims.
- Documentation and user-facing limitations match the deployed product.

## 16. External dependencies

Provider contracts, number inventory, route pricing, SMS/MMS, payment processing, carrier/regulatory approval, SIM/eSIM provisioning, emergency services and production hosting are external dependencies. A repository plan or adapter interface is not evidence that an external provider has approved or enabled the service.
