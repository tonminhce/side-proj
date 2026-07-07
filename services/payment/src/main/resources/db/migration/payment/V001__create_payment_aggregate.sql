-- V001__create_payment_aggregate.sql — Story 3.1 (FR-25, ADR-03, ADR-14, ADR-22)
-- PaymentService canonical tables. Schema is intentionally skeletal — Story 3.1 is a foundation
-- story; the JPA entity lands in a follow-up. Flyway is the schema authority; JPA is configured
-- with `ddl-auto: validate` so any entity-table mismatch fails fast at boot.
--
-- Architectural references:
--   * ADR-03 (database-per-service): payment_db is the only DB this service touches.
--     payment_aggregate.order_uuid is a cross-service reference to order_db — NO FK (separate DB).
--   * ADR-14 (per-service outbox): `outbox` + `processed_event` shape mirrors catalog / cart /
--     checkout V001 verbatim. Modulith outbox bridge publishes to Kafka.
--   * FR-25: stable idempotency-key contract lands in IdempotencyKey.java (no DB column needed —
--     the key is derived on the wire, not stored).
--   * Story 3.2 (webhook dedup, ADR-21) adds the webhook_dedup table.
--   * Story 3.5 (HMAC-signed outbox, ADR-20) reuses this migration's `outbox` table.
--
-- RootEntity legacy `id` column: util/RootEntity declares
--   @Column(name="id", insertable=false, updatable=false). Hibernate `validate` fails if the column is
--   missing; we add a nullable `id BIGINT` (Hibernate never reads/writes it). Mirrors checkout V001.

-- =========================================================================
-- payment_aggregate — v1 placeholder (Story 3.1 ships no JPA entity for it yet;
-- AC #1 needs at least one table so Flyway has something to validate and the
-- service is a real per-service boundary, not an empty shell).
-- =========================================================================
CREATE TABLE payment_aggregate (
    uuid                     BIGINT       PRIMARY KEY,                 -- Snowflake ID from BaseEntity
    id                       BIGINT,                                  -- RootEntity legacy id (insertable=false; nullable)
    tenant_id                VARCHAR(64)  NOT NULL DEFAULT 'default', -- ADR-07 deferred to Epic 5; v1 single-tenant.
    order_uuid               BIGINT       NOT NULL,                   -- cross-service reference to order_db (NO FK, ADR-03)
    amount_cents             BIGINT       NOT NULL CHECK (amount_cents >= 0), -- minor units (đồng for VND)
    currency                 CHAR(3)      NOT NULL,                    -- ISO-4217
    stripe_payment_intent_id VARCHAR(128),                            -- nullable until Story 3.3 wires the real SDK
    status                   VARCHAR(32)  NOT NULL
        CHECK (status IN ('CREATED','AUTHORIZED','CAPTURED','REFUNDED','FAILED')),
    version                  BIGINT       NOT NULL DEFAULT 0,          -- optimistic-lock placeholder; Story X.Y wires @Version
    created_by               VARCHAR(36),
    created_at               TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by               VARCHAR(36),
    updated_at               TIMESTAMP,
    deleted_by               VARCHAR(36),
    deleted_at               TIMESTAMP,
    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted               BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_payment_aggregate_order ON payment_aggregate(order_uuid);

-- =========================================================================
-- outbox — ADR-14 (canonical column set; mirrors catalog / cart / checkout V001 verbatim).
-- =========================================================================
CREATE TABLE outbox (
    id                  BIGSERIAL    PRIMARY KEY,
    aggregate_type      VARCHAR(64)  NOT NULL,                -- 'Payment'
    aggregate_id        BIGINT       NOT NULL,                -- Snowflake ID
    event_type          VARCHAR(128) NOT NULL,                -- 'payment.authorized' etc. (Story 3.2/3.5)
    event_id            BIGINT       NOT NULL UNIQUE,          -- Snowflake ID; idempotency key
    payload             JSONB        NOT NULL,
    signatures          JSONB,                                -- ADR-20 HMAC map {"hmac_sha256": "..."} (Story 3.5)
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    published_at        TIMESTAMP                             -- NULL until bridge acks
);
CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate   ON outbox(aggregate_type, aggregate_id);

-- =========================================================================
-- processed_event — ADR-04 (consumer idempotency; empty in Story 3.1)
-- =========================================================================
CREATE TABLE processed_event (
    id                  BIGSERIAL    PRIMARY KEY,
    event_id            BIGINT       NOT NULL UNIQUE,          -- Snowflake ID of consumed event
    event_type          VARCHAR(128) NOT NULL,
    processed_at        TIMESTAMP    NOT NULL DEFAULT now(),
    consumer            VARCHAR(128) NOT NULL
);
CREATE INDEX idx_processed_event_consumer ON processed_event(consumer, processed_at);