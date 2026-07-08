-- V002__create_outbox.sql — Story 4.1
-- Modulith outbox table for the order service. Mirrors the canonical schema from
-- services/catalog/V001__create_catalog_tables.sql. ADR-14 (per-service outbox) + ADR-04
-- (consumer-side idempotency via processed_event; landed in catalog/cart/checkout already).

CREATE TABLE outbox (
    id                  BIGSERIAL    PRIMARY KEY,
    aggregate_type      VARCHAR(64)  NOT NULL,                -- 'Order'
    aggregate_id        BIGINT       NOT NULL,                -- Snowflake ID (orderUuid)
    event_type          VARCHAR(128) NOT NULL,                -- 'order.placed', 'order.paid'
    event_id            BIGINT       NOT NULL UNIQUE,         -- Snowflake ID; idempotency key
    payload             JSONB        NOT NULL,
    signatures          JSONB        NOT NULL DEFAULT '{}'::jsonb,   -- ADR-20 HMAC envelope signature
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    published_at        TIMESTAMP                            -- NULL until bridge acks
);

-- Partial index: bridge scans unpublished rows in chronological order.
CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate   ON outbox(aggregate_type, aggregate_id);