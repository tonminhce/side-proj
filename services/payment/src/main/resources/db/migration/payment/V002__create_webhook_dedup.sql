-- V002__create_webhook_dedup.sql — Story 3.2 (FR-26, ADR-21, NFR-IDEM-1, R-03)
-- Stripe webhook dedup table. Picks up exactly where V001's
-- "Story 3.2 (webhook dedup, ADR-21) adds the webhook_dedup table" comment (V001:13) left off.
--
-- Architectural references:
--   * ADR-03 (database-per-service): webhook_dedup lives in payment_db; no cross-service join.
--   * ADR-21 (webhook dedup): PK on Stripe's opaque event.id is the dedup contract.
--   * architecture.md:298 — "Idempotency table | `processed_event` (or `webhook_dedup` for Stripe)".
--   * architecture.md:624-645 — Canonical existsBy/append pattern; this is the producer-side
--     Stripe-shaped sibling of catalog's processed_event (Snowflake BIGINT key).
--   * FR-26 + R-03 — solves the Stripe 3-day retry storm (double-capture on saga replay).
--
-- ponytail: webhook_dedup has NO surrogate `id BIGINT` — the natural key IS the dedup contract
-- (Stripe event.id is globally unique within a livemode; per AC #6 we don't split it further).
-- The catalog's processed_event carries a surrogate because its UNIQUE event_id sits alongside a
-- separate PK; the two shapes are deliberately different.

-- =========================================================================
-- webhook_dedup — Stripe producer-side idempotency (FR-26, ADR-21)
-- =========================================================================
CREATE TABLE webhook_dedup (
    event_id      VARCHAR(128) PRIMARY KEY,                       -- Stripe `event.id` (e.g. evt_abc123); copied verbatim, case-sensitive, no transformation
    event_type    VARCHAR(128) NOT NULL,                          -- `payment_intent.succeeded`, `charge.refunded`, etc.; from payload.type
    received_at   TIMESTAMP    NOT NULL DEFAULT now(),            -- when the row was inserted (F1: prefer the boring accurate name over the symmetric-but-confusing `created_at`)
    livemode      BOOLEAN      NOT NULL,                          -- Stripe's livemode flag; observability only — PK is event_id alone (AC #6)
    processed_at  TIMESTAMP                                       -- nullable; NULL = handler crashed mid-processing; future replay redos the side-effects (out of scope for Story 3.2)
);
CREATE INDEX idx_webhook_dedup_received_at ON webhook_dedup(received_at DESC);   -- "last 24h" ops query; pay the index now, don't defer (F1)

-- =========================================================================
-- webhook_delivery_log — TEST-OBSERVABILITY SHIM (deleted by Story 3.5 when real outbox events land)
-- Per Task 5: this is the firehose the use case writes to prove the side-effect path runs exactly
-- once per event.id. Production logic MUST NEVER read this table.
-- =========================================================================
CREATE TABLE webhook_delivery_log (
    event_id              VARCHAR(128) NOT NULL,                  -- Stripe event.id (no FK — webhook payloads are keyed on Stripe's space, not ours)
    event_type            VARCHAR(128) NOT NULL,
    received_at           TIMESTAMP    NOT NULL DEFAULT now(),
    side_effects_recorded TEXT                                      -- free-form for the placeholder; replaced by outbox payload JSON in Story 3.5
);
CREATE INDEX idx_webhook_delivery_log_event ON webhook_delivery_log(event_id);