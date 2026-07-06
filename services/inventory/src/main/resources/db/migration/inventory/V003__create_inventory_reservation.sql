-- V003__create_inventory_reservation.sql — Story 1.6 (FR-9, ADR-12, DI-01 root-cause fix)
-- The reservation is a NEW aggregate (FR-9): it tracks held stock separately from
-- the inventory_ledger source-of-truth. A reservation has a finite TTL (default 15 min,
-- configurable via MAX_RESERVATION_TTL_MINUTES / inventory.reservation.ttl-minutes).
-- The sweeper job expires stale reservations and emits inventory.released events.

-- Architecture: per epics.md line 513 + Story 1.5's V001 conventions
--   * snake_case plural tables (architecture.md line 291)
--   * uq_<table>_<column> unique constraint naming (architecture.md line 296)
--   * Snowflake `uuid` PK + nullable legacy `id` (RootEntity contract, Story 1.5 V001 line 28-35)
--   * tenant_id from day one (architecture-detail.md line 78)

CREATE TABLE inventory_reservation (
    uuid                  BIGINT       PRIMARY KEY,            -- Snowflake ID (BaseEntity @Id)
    id                    BIGINT,                             -- RootEntity legacy id (insertable=false; nullable)
    variant_id            BIGINT       NOT NULL,               -- cross-service reference (no FK)
    warehouse_id          BIGINT       NOT NULL REFERENCES warehouses(uuid),
    quantity              BIGINT       NOT NULL CHECK (quantity > 0),    -- reserved units; >0 by CHECK
    status                VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',         -- ACTIVE | RELEASED | COMMITTED
    expires_at            TIMESTAMP    NOT NULL,               -- now() + TTL at insert; sweeper scans this
    saga_step_id          VARCHAR(128) NOT NULL,               -- ADR-11 idempotency key (stable across retries)
    order_uuid            BIGINT,                              -- optional FK to order (cross-service; no constraint)
    tenant_id             VARCHAR(64)  NOT NULL DEFAULT 'default',
    created_by            VARCHAR(36),
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by            VARCHAR(36),
    updated_at            TIMESTAMP,
    deleted_by            VARCHAR(36),
    deleted_at            TIMESTAMP,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_inventory_reservation_saga_step UNIQUE (saga_step_id)
);
-- Sweeper scans ACTIVE + expires_at < now() (order by expires_at for sequential processing).
CREATE INDEX idx_inventory_reservation_sweeper
    ON inventory_reservation(expires_at) WHERE status = 'ACTIVE';
-- Variant-scoped queries (saga step re-derivation, future Story 1.7 multi-warehouse breakdown).
CREATE INDEX idx_inventory_reservation_variant
    ON inventory_reservation(variant_id, status);
-- Composite for warehouse-scoped queries.
CREATE INDEX idx_inventory_reservation_variant_warehouse
    ON inventory_reservation(variant_id, warehouse_id, status);

-- PONYTAIL NOTES (do NOT add):
-- * NO tenant_id on outbox/processed_event (event routing metadata, not business) — Story 1.2's
--   convention verified at catalog's V002.
-- * NO `on_hand` column — the ledger is the source of truth (FR-8 / Story 1.5 V001 line 67-71).
-- * NO FK on variant_id (cross-service: catalog_db.products is in a separate database; ADR-03).
-- * NO FK on order_uuid — checkout_db.orders is in a separate database; saga_step_id is the
--   cross-service idempotency key per ADR-11.
-- * NO `committed_at` column — committed reservations transition to `inventory_ledger` with
--   reason='allocate' (Story 4.1 saga step); the reservation row stays ACTIVE until release.
--   Story 4.1 may add a `committed_at` if saga lifecycle demands it; not Story 1.6.