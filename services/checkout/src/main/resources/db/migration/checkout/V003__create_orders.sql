-- V003__create_orders.sql — Story 2.5 / FR-22, FR-23 (ADR-12)
-- Adds the `orders` aggregate + partial index for the recovery query. Additive migration;
-- V001 + V002 immutable.
--
-- Architectural references:
--   * ADR-03 (database-per-service) — `orders.cart_uuid` and `orders.checkout_uuid` are NO FK
--     (checkout_uuid lives in the same DB but is a saga co-located reference; cart_uuid crosses to
--     cart_db).
--   * ADR-12 (intra-Modulith saga) — `status` is the FSM state; `version` is the @Version lock;
--     the partial index `idx_orders_inflight_updated` is what the recovery runner's
--     `findStuckOrders` query uses.
--   * Story 2.5 / AC #1 — orders carry `paymentIntentId` (denormalized copy from Checkout) so
--     downstream consumers can correlate without a join.
--
-- DO NOT amend V001 or V002 (Flyway = immutable applied migrations).

-- =========================================================================
-- orders — the Order aggregate root (FR-22, FR-23)
-- =========================================================================
CREATE TABLE orders (
    uuid                BIGINT       PRIMARY KEY,                 -- Snowflake ID from BaseEntity
    id                  BIGINT,                                   -- RootEntity legacy id (insertable=false; nullable)
    tenant_id           VARCHAR(64)  NOT NULL DEFAULT 'default',
    cart_uuid           BIGINT       NOT NULL,                    -- cross-service reference (NO FK)
    checkout_uuid       BIGINT       NOT NULL UNIQUE,             -- one order per checkout (saga idempotency)
    payment_intent_id   VARCHAR(64),                              -- denormalized copy of checkout.payment_intent_id (Story 2.4)
    recipient_name      VARCHAR(255) NOT NULL,
    phone               VARCHAR(32)  NOT NULL,
    address_line_1      VARCHAR(512) NOT NULL,
    address_line_2      VARCHAR(512),
    city                VARCHAR(128) NOT NULL,
    district            VARCHAR(128),
    province            VARCHAR(128) NOT NULL,
    country             VARCHAR(2)   NOT NULL DEFAULT 'VN',
    postal_code         VARCHAR(16),
    status              VARCHAR(32)  NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED','STOCK_RESERVED','PAYMENT_PENDING','PAID','FAILED','CANCELLED','EXPIRED','COMPENSATED')),
    version             BIGINT       NOT NULL DEFAULT 0,          -- optimistic concurrency (@Version)
    failure_saga_step   VARCHAR(64),                              -- saga step that drove the failure
    failure_reason      VARCHAR(64),                              -- free-text reason (e.g. INSUFFICIENT_STOCK)
    created_by          VARCHAR(36),
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by          VARCHAR(36),
    updated_at          TIMESTAMP,
    deleted_by          VARCHAR(36),
    deleted_at          TIMESTAMP,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_orders_tenant_cart       ON orders(tenant_id, cart_uuid);
CREATE INDEX idx_orders_checkout_uuid     ON orders(checkout_uuid);
-- Partial index that backs the recovery runner's `findStuckOrders(cutoff)` query
-- (Story 2.5 / AC #5).
CREATE INDEX idx_orders_inflight_updated
    ON orders(updated_at)
    WHERE status IN ('CREATED', 'STOCK_RESERVED', 'PAYMENT_PENDING');