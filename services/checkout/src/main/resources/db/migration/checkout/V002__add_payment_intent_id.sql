-- V002__add_payment_intent_id.sql — Story 2.4 / FR-20 (CheckoutService owns PaymentIntent lifecycle).
-- Adds payment_intent_id to the checkouts aggregate so the checkout row carries the Stripe
-- identifier returned by PaymentIntent.create(). `pi_...` ids are <=27 chars; VARCHAR(64) is
-- safe. Nullable — the column is only populated after Story 2.4 lands.
--
-- Architectural references:
--   * ADR-04 atomicity — payment_intent_id is set in the same transaction as the row INSERT and
--     outbox append.
--   * ADR-11 / NFR-IDEM-2 — stable idempotency key (checkoutUuid, "stripe.payment_intent.create")
--     drives retries so Stripe returns the same PaymentIntent.
--   * R-12 pin — stripe-java:33.1.0 in pom.xml.
--
-- DO NOT amend V001 (Flyway = immutable applied migrations).

ALTER TABLE checkouts ADD COLUMN payment_intent_id VARCHAR(64);