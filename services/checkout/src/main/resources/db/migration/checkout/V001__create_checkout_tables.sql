-- V001__create_checkout_tables.sql — Story 2.3 (FR-19, FR-21; ADR-01, ADR-04, ADR-14, ADR-22)
-- CheckoutService canonical tables. Flyway is the schema authority; JPA is `ddl-auto: validate`
-- so any entity-table mismatch fails fast at boot.
--
-- Architectural references:
--   * ADR-03 (database-per-service): checkout_db is the only DB this service touches.
--     checkouts.cart_uuid is a cross-service reference to cart_db.carts — NO FK (separate database).
--   * ADR-14 (per-service outbox): the `outbox` + `processed_event` shape mirrors cart V001 verbatim.
--   * ADR-22 (saga foundation): Checkout is a finite-state machine
--     (CREATED → PAYMENT_PENDING → PAID/FAILED/CANCELLED/EXPIRED). Story 2.3 only INSERTs in
--     PAYMENT_PENDING; Story 2.5's saga orchestrator drives terminal transitions.
--   * Story 2.3 / FR-19: single-page checkout — one POST returns {checkoutId, status}.
--   * Story 2.3 / FR-21: checkoutId polling — GET /api/checkouts/{uuid} for status.
--
-- RootEntity legacy `id` column: util/RootEntity declares
--   @Column(name="id", insertable=false, updatable=false). Hibernate `validate` fails if the column is
--   missing; we add a nullable `id BIGINT` (Hibernate never reads/writes it). Mirrors cart V001.

-- =========================================================================
-- checkouts — the checkout aggregate root (FR-19, FR-21)
-- =========================================================================
CREATE TABLE checkouts (
    uuid                BIGINT       PRIMARY KEY,                 -- Snowflake ID from BaseEntity
    id                  BIGINT,                                   -- RootEntity legacy id (insertable=false; nullable)
    tenant_id           VARCHAR(64)  NOT NULL DEFAULT 'default',
    cart_uuid           BIGINT       NOT NULL,                    -- cross-service reference (NO FK to cart_db.carts)
    user_id             VARCHAR(64),                              -- auth user id (nullable — guest checkout per FR-19)
    guest_cart_id       VARCHAR(64),                              -- cookie UUID (nullable when user is logged in)
    status              VARCHAR(32)  NOT NULL DEFAULT 'PAYMENT_PENDING'
        CHECK (status IN ('CREATED','PAYMENT_PENDING','PAID','FAILED','CANCELLED','EXPIRED')),
    version             BIGINT       NOT NULL DEFAULT 0,          -- optimistic concurrency (@Version)
    stripe_client_secret TEXT,                                    -- ADR-20 + Story 2.4 forward-compat passthrough
    recipient_name      VARCHAR(255) NOT NULL,
    phone               VARCHAR(32)  NOT NULL,
    address_line_1      VARCHAR(512) NOT NULL,
    address_line_2      VARCHAR(512),
    city                VARCHAR(128) NOT NULL,
    district            VARCHAR(128),
    province            VARCHAR(128) NOT NULL,
    country             VARCHAR(2)   NOT NULL DEFAULT 'VN',
    postal_code         VARCHAR(16),
    created_by          VARCHAR(36),
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by          VARCHAR(36),
    updated_at          TIMESTAMP,
    deleted_by          VARCHAR(36),
    deleted_at          TIMESTAMP,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_checkouts_tenant_cart    ON checkouts(tenant_id, cart_uuid);
CREATE INDEX idx_checkouts_tenant_user    ON checkouts(tenant_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_checkouts_status_updated ON checkouts(status, updated_at) WHERE status IN ('PAYMENT_PENDING');

-- =========================================================================
-- outbox — ADR-14 (canonical column set; mirrors cart V001 verbatim)
-- =========================================================================
CREATE TABLE outbox (
    id                  BIGSERIAL    PRIMARY KEY,
    aggregate_type      VARCHAR(64)  NOT NULL,                -- 'Checkout'
    aggregate_id        BIGINT       NOT NULL,                -- Snowflake ID
    event_type          VARCHAR(128) NOT NULL,                -- 'checkout.started'
    event_id            BIGINT       NOT NULL UNIQUE,          -- Snowflake ID; idempotency key
    payload             JSONB        NOT NULL,
    signatures          JSONB,                                -- ADR-20 HMAC map {"hmac_sha256": "..."}
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    published_at        TIMESTAMP                             -- NULL until bridge acks
);
CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate   ON outbox(aggregate_type, aggregate_id);

-- =========================================================================
-- processed_event — ADR-04 (consumer idempotency; empty in Story 2.3 — Story 2.5 saga listener
-- will write to this table on consume per NFR-IDEM-1)
-- =========================================================================
CREATE TABLE processed_event (
    id                  BIGSERIAL    PRIMARY KEY,
    event_id            BIGINT       NOT NULL UNIQUE,          -- Snowflake ID of consumed event
    event_type          VARCHAR(128) NOT NULL,
    processed_at        TIMESTAMP    NOT NULL DEFAULT now(),
    consumer            VARCHAR(128) NOT NULL
);
CREATE INDEX idx_processed_event_consumer ON processed_event(consumer, processed_at);