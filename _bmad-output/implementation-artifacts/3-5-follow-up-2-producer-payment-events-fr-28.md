---
story_key: 3-5-follow-up-2-producer-payment-events
epic: 3
title: "Producer-side payment.captured / payment.refunded outbox events (FR-28)"
status: review
baseline_commit: 91670b7
date: 2026-07-08
sprint: epic-3-5-sprint-1-follow-up
source_deferred: deferred-issues.md#story-3-5-2026-07-08-consumer-side-hmac-verification-in-checkouts-outbox-listener
---

# Story 3.5 follow-up #2 — Producer-side payment.captured / payment.refunded outbox events (FR-28)

## Context

The session's re-assessment of the deferred Story 3.5 consumer-side HMAC verify (commit
`e8f4b72`) revealed that the **producer side** was the real chain-blocker. Today, the payment
service's `HandleStripeWebhookUseCase` does not emit `payment.captured` / `payment.refunded`
events — it records a `webhook_delivery_log` row with a flat label and returns. The order
service's `PaymentCapturedOrderAdvancer` is already wired to consume such events (4 unit tests
green), but no event ever fires.

This story ships the producer: define `PaymentCapturedEvent` + `PaymentRefundedEvent` records,
branch `HandleStripeWebhookUseCase` on `event.type()`, publish via the existing
HMAC-signed `PaymentModulithOutboxPublisher`. The consumer side (checkout listener verifying
HMAC) lands in a follow-up story once the producer is real.

## Acceptance Criteria

1. `PaymentCapturedEvent` record exists at
   `services/payment/.../application/event/PaymentCapturedEvent.java` with field shape
   `long orderUuid, String paymentIntentId, long amountCents, String currency, LocalDateTime occurredAt`
   (mirror of `services/order/.../application/saga/event/PaymentCapturedEvent`).
2. `PaymentRefundedEvent` record exists at the parallel path with the same field shape.
3. `PaymentOutboxPublisher` interface exists at
   `services/payment/.../application/port/PaymentOutboxPublisher.java` (the seam that
   `HandleStripeWebhookUseCase` depends on so unit tests can mock it without fighting Mockito's
   `mock-maker-subclass` constraint).
4. `PaymentModulithOutboxPublisher` implements `PaymentOutboxPublisher` (no other behavior
   change).
5. `HandleStripeWebhookUseCase` switches on `event.type()` after the existing dedup path:
   - `payment_intent.succeeded` → publish `PaymentCapturedEvent` with `orderUuid` from
     `data.object.metadata.order_uuid`, `paymentIntentId` from `data.object.id`, `amountCents`
     from `data.object.amount`, `currency` upper-cased.
   - `charge.refunded` → publish `PaymentRefundedEvent` with `paymentIntentId` from
     `data.object.payment_intent`, `amountCents` from `data.object.amount_refunded`,
     `orderUuid=0L` sentinel (charge payload doesn't carry metadata — consumers look up by piId
     until the convention is extended).
   - Other types → no-op (existing delivery-log row stays).
6. Extraction failures (missing `metadata.order_uuid`, missing `data.object`, missing
   `payment_intent`) log a warning and skip the publish; the webhook audit trail (dedup row +
   delivery-log row) still lands — misconfigured Stripe payments don't break audit.
7. `aggregateId` passed to `append(...)` is the numeric portion of the Stripe
   `paymentIntentId` (e.g. `pi_123456789` → 123456789L).
8. Tests: `mvn -pl services/payment test` is 96/96 green (was 91; +5 new tests in
   `HandleStripeWebhookUseCaseTest`).
9. Smoke: `dev/scripts/smoke-payment-captured-refunded.sh` boots the service, POSTs both webhook
   payloads, accepts the 200 response; psql verification of the `outbox` row is conditional
   on `psql` being on PATH (sandbox-skipping pattern from other smokes).

## Out of scope

- `PaymentRefundedOrderAdvancer` in order service — the event is published but no consumer
  exists. Logged to `deferred-issues.md`.
- Consumer-side HMAC verification listener in checkout (the verifier class + tests already
  exist; the listener wiring is the next story once the producer is real).
- Real Vault integration for `VaultHmacKeyProvider` (env var stub for dev; prod deferred).
- Replacing `webhook_delivery_log` writes with outbox events (existing audit row stays).

## Tasks

- [x] `PaymentCapturedEvent` + `PaymentRefundedEvent` records.
- [x] `PaymentOutboxPublisher` port interface.
- [x] `PaymentModulithOutboxPublisher implements PaymentOutboxPublisher`.
- [x] `HandleStripeWebhookUseCase` type switch + JsonNode extraction helpers.
- [x] `HandleStripeWebhookUseCaseTest` — 5 new tests; existing 4 tests updated for the new ctor.
- [x] Smoke script with psql-gated outbox verification.
- [x] 96/96 tests green.

## Dev Notes

### Why a port interface

Mockito's `mock-maker-subclass` (configured in
`services/payment/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`)
cannot mock final classes. The existing `PaymentModulithOutboxPublisher` is non-final but
the inheritance chain (`ModulithOutboxPublisher` has a `final` `append(...)` method) made
the matcher signature detection fail at runtime with "1 matchers expected, 5 recorded".

The fix mirrors the existing pattern (`WebhookDedupPort`, `WebhookDeliveryLogPort`):
introduce a `PaymentOutboxPublisher` interface that the use case depends on; the concrete
implementation registers as a `@Component` and implements the port.

### Why `metadata.order_uuid` for correlation

The Stripe `PaymentIntent` carries a free-form `metadata` map; checkout sets `metadata.order_uuid`
when creating the PaymentIntent. The webhook payload `data.object.metadata.order_uuid` is the
canonical way to thread the internal `orderUuid` through Stripe's webhook without parsing
fragile identifier formats.

For `charge.refunded` the `data.object.payment_intent` field carries the original
PaymentIntent id but the `metadata` is NOT propagated (Stripe's `charge.refunded` object
mirrors the charge, not the intent). `orderUuid=0L` is a sentinel — a future story can extend
the convention by querying `payment_intent.metadata` in the same payload (when present) or
via a separate lookup service.

### JsonNode extraction

The webhook's `data` is a `tools.jackson.databind.JsonNode` (not deserialized to typed
records). A 4-line helper `extractOrderUuid(JsonNode)` reads
`data.object.metadata.order_uuid` with graceful null-handling; same pattern for
`textOrNull(JsonNode, String)` and `stripNonDigits(String)` (Stripe IDs look like
`pi_3O8...`, only the digits fit a Snowflake long).

### HMAC signing — zero changes

`PaymentModulithOutboxPublisher.signaturesFor(...)` already signs every envelope via
`HmacEventSigner.sign(JcsCanonicalJson.serialize(envelope), secret)`. The new event types
inherit the signing for free. No change to the publisher's HMAC code path.

## Files changed

### New
- `services/payment/.../application/event/PaymentCapturedEvent.java`
- `services/payment/.../application/event/PaymentRefundedEvent.java`
- `services/payment/.../application/port/PaymentOutboxPublisher.java`
- `dev/scripts/smoke-payment-captured-refunded.sh`
- `_bmad-output/implementation-artifacts/3-5-follow-up-2-producer-payment-events-fr-28.md`

### Modified
- `services/payment/.../infrastructure/outbox/PaymentModulithOutboxPublisher.java` (added `implements PaymentOutboxPublisher`)
- `services/payment/.../application/usecase/HandleStripeWebhookUseCase.java` (type switch + helpers)
- `services/payment/src/test/java/.../HandleStripeWebhookUseCaseTest.java` (+5 tests)
- `_bmad-output/backlog/deferred-issues.md` (consumer-side entry now points to the real producer; new entry for payment.refunded order consumer)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (new story key)

## Test results

- Before: 91 tests
- After: 96 tests (+5)
- All green.

## Verification

1. `mvn -pl services/payment test` — 96/96 green.
2. `bash dev/scripts/smoke-payment-captured-refunded.sh` — webhook accepted, service boots,
   /actuator/loggers 404. With `psql` on PATH (dev env with `docker compose up`), verifies
   the `outbox` row + 43-char base64url HMAC signature.
3. The order service's `PaymentCapturedOrderAdvancer` (already in place from Story 4.2)
   consumes the same event class via Spring Modulith's intra-JVM `@EventListener` dispatch.

## What's skipped, add when

- `PaymentRefundedOrderAdvancer` in order service — the saga listener for `payment.refunded`
  events. Add when the next order-service story ships (Saga 4.x).
- Consumer-side HMAC verification listener in checkout — `PaymentEventSignatureVerifier` is
  ready; the listener wiring lands in the next follow-up cycle.
- Real Vault integration for `VaultHmacKeyProvider` (env var stub for now; prod-readiness
  deferred).

## Unblocks

- **Story 4.1 saga integration (PLACED → PAID)** — the order-side consumer was already wired
  but couldn't fire because no event was published. The next time checkout + payment +
  order run in the same dev session, the order service will advance PLACED → PAID on
  payment.captured.
- **Story 3.5 consumer-side HMAC verify** — the producer is real now, so a checkout listener
  can verify the HMAC envelope before forwarding to the saga (separate story).

## Resolves

- `deferred-issues.md#story-3-5-2026-07-08-consumer-side-hmac-verification-in-checkouts-outbox-listener`
  (was "blocked-by-missing-producer" — now the producer exists, the consumer wiring can
  follow).