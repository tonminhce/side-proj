-- V001__create_order_event_log.sql — Story 4.1 / FR-30, FR-31
-- Append-only event log + immutable price snapshot. Mirrors the canonical schema in
-- architecture.md:299 row "Order state transition log".

CREATE TABLE order_state_transition (
    id          BIGSERIAL PRIMARY KEY,
    order_uuid  BIGINT NOT NULL,
    from_state  VARCHAR(32),
    to_state    VARCHAR(32) NOT NULL,
    saga_step   VARCHAR(64) NOT NULL,
    event_id    BIGINT NOT NULL UNIQUE,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_state_transition_order_uuid
    ON order_state_transition (order_uuid, created_at DESC);

-- Append-only at the application layer (no JPA setter on the use case path).
-- Future hardening: a Postgres BEFORE UPDATE trigger to enforce at the DB layer.

CREATE TABLE order_price_snapshot (
    order_uuid        BIGINT PRIMARY KEY,
    list_price_cents  BIGINT NOT NULL,
    promo_codes       TEXT,
    tax_cents         BIGINT NOT NULL,
    shipping_cents    BIGINT NOT NULL,
    total_cents       BIGINT NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    captured_at       TIMESTAMP NOT NULL DEFAULT now()
);

-- FR-31: no `updated_at` column; the snapshot is immutable after order.placed.