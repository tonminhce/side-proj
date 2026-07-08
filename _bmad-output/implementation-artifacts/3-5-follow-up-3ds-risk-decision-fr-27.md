---
story_key: 3-5-follow-up-3ds-risk-decision
epic: 3
title: "3DS risk-decision logic in RealStripePaymentAdapter (FR-27)"
status: review
baseline_commit: 934c9ca
date: 2026-07-08
sprint: epic-3-5-sprint-1-follow-up
source_deferred: deferred-issues.md#story-3-5-2026-07-08-3ds-risk-decision-logic-in-realstripepaymentadapter
---

# Story 3.5 follow-up — 3DS risk-decision logic (FR-27)

## Context

Story 3.5 shipped HMAC producer-side signing (ADR-20) but deferred the 3DS step-up wiring
in `RealStripePaymentAdapter.authorize(...)`. FR-27 mandates a merchant-side decision for
EEA + high-amount + elevated-risk transactions (PSD2 RTS Article 18). This story closes the gap.

## Acceptance Criteria

1. `AuthorizePaymentCommand` accepts a nullable `country` (ISO-3166-1 alpha-2) and a nullable
   `RiskLevel` enum (`LOW` / `ELEVATED` / `HIGHEST`).
2. Existing 5-arg ctor preserved for backward compatibility (calls 7-arg ctor with nulls).
3. `withCountry(String)` + `withRiskLevel(RiskLevel)` helpers return new records (records are
   immutable; the helpers preserve the identity pattern of `withIdempotencyKey`).
4. `ThreeDSecureDecision.shouldRequire(country, amountCents, riskLevel)` returns true iff:
   - country is in the EEA list (EU + IS + LI + NO), AND
   - amountCents >= 3000 (low-value exemption floor), AND
   - riskLevel ∈ {ELEVATED, HIGHEST}.
5. `RealStripePaymentAdapter.authorize(...)` evaluates the decision; when true, sets
   `payment_method_options.card.request_three_d_secure=ANY` on the PaymentIntent.
6. `PaymentResult` gains a nullable `requiresActionUrl` field; the adapter surfaces
   `nextAction.redirectToUrl.url` when Stripe returns `requires_action`.
7. Existing tests stay green; 23 new unit tests cover the decision matrix + test-double 3DS branch.

## Out of scope

- `OrderStatus.PAYMENT_REQUIRES_ACTION` enum + saga transition (separate story; see deferred-issues.md).
- `riskLevel` plumbing from checkout/BFF — the upstream signal comes from a future risk-scoring
  service (currently null by default; Stripe's SCA engine handles unflagged transactions).
- Production 3DS challenge iframe integration testing (Stripe Elements is in Story 3.3; the
  challenge URL is consumed by Elements in the BFF, not by the payment service itself).

## Tasks

- [x] Add `RiskLevel` enum (LOW / ELEVATED / HIGHEST).
- [x] Add `ThreeDSecureDecision` pure function with full truth-table tests (21 cases).
- [x] Extend `AuthorizePaymentCommand` with 7-arg canonical ctor; preserve 5-arg backward-compat;
      add `withCountry` / `withRiskLevel` helpers; add ISO-3166-1 alpha-2 validation in compact ctor.
- [x] Extend `PaymentResult` with nullable `requiresActionUrl`.
- [x] Update `RealStripePaymentAdapter.authorize(...)` to set `request_three_d_secure=ANY` when
      decision triggers; surface `nextAction.redirectToUrl.url`.
- [x] Update test-double `StripePaymentAdapter` to record new fields + return REQUIRES_ACTION + stub URL.
- [x] Tests: 91/91 green (was 68; added 23).

## Dev Notes

### Stripe SDK 28.x API surface (verified via javap)

- `payment_method_options.card.request_three_d_secure` is an enum:
  `PaymentIntentCreateParams.PaymentMethodOptions.Card.RequestThreeDSecure.ANY / AUTOMATIC / CHALLENGE`.
- Builder path:
  ```java
  PaymentIntentCreateParams.builder()
      .setPaymentMethodOptions(
          PaymentIntentCreateParams.PaymentMethodOptions.builder()
              .setCard(
                  PaymentIntentCreateParams.PaymentMethodOptions.Card.builder()
                      .setRequestThreeDSecure(
                          PaymentIntentCreateParams.PaymentMethodOptions.Card.RequestThreeDSecure.ANY)
                      .build())
              .build())
      .build();
  ```
- `nextAction.redirectToUrl.url` is `PaymentIntent.getNextAction().getRedirectToUrl().getUrl()`.

### PSD2 RTS Article 18 (low-value exemption)

The 30 EUR floor (3000 minor units at 2-decimal currency) is the merchant-side override; below
it the merchant may skip 3DS but assumes liability for fraud. We treat anything below as
"don't request 3DS" — Stripe's SCA engine still applies its own low-value heuristic via
`request_three_d_secure=AUTOMATIC` when the merchant opts out of the override.

### Backward compat

The 5-arg `AuthorizePaymentCommand` ctor is preserved so the 5 existing call sites
(use case test, adapter test, payment-port contract test) compile without edits. The 7-arg
canonical ctor is the new signature; `withIdempotencyKey`, `withCountry`, `withRiskLevel` all
return new records to preserve ADR-11 idempotency semantics.

## Files changed

- `services/payment/.../application/port/RiskLevel.java` (new)
- `services/payment/.../application/port/ThreeDSecureDecision.java` (new)
- `services/payment/.../application/port/AuthorizePaymentCommand.java` (extended)
- `services/payment/.../application/port/PaymentResult.java` (extended)
- `services/payment/.../infrastructure/stripe/RealStripePaymentAdapter.java` (3DS wiring)
- `services/payment/.../infrastructure/stripe/StripePaymentAdapter.java` (test-double 3DS branch)
- `services/payment/src/test/java/.../ThreeDSecureDecisionTest.java` (new, 21 tests)
- `services/payment/src/test/java/.../StripePaymentAdapterTest.java` (+2 tests)
- `services/payment/src/test/java/.../AuthorizePaymentCommandTest.java` (+1 country-validation test)

## Test results

- Before: 68 tests
- After: 91 tests (+23)
- All green.

## Resolves

- `deferred-issues.md#story-3-5-2026-07-08-3ds-risk-decision-logic-in-realstripepaymentadapter`
  (HIGH → fixed)