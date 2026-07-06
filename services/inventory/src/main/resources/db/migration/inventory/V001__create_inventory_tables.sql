-- V001__create_inventory_tables.sql — Story 1.5 (FR-8, ADR-12, ADR-14, FR-13)
-- InventoryService canonical tables. Schema is forward-compatible with FR-10 multi-warehouse
-- (Story 1.7) but seeds ONE warehouse at boot (ADR-06 single-warehouse v1 default).
-- Flyway is the schema authority; JPA is configured with `ddl-auto: validate` so any
-- entity-table mismatch fails fast at boot.
--
-- Architectural references:
--   * ADR-03 (database-per-service): inventory_db is the only DB this service touches.
--   * ADR-12 (per-warehouse ledger): inventory_ledger is the SOLE source of truth; on_hand
--     is SUM(delta) per (variant_id, warehouse_id) — NEVER a column. Mutating on_hand would
--     defeat the double-entry reconciliation property.
--   * ADR-14 (per-service outbox): the `outbox` table shape matches architecture.md line 297
--     and catalog's outbox shape verbatim — do not rename columns.
--   * ADR-04 (consumer idempotency): `processed_event.event_id` is the unique idempotency key.
--     The `inventory_ledger.event_id` UNIQUE constraint is the CROSS-AGGREGATE dedup beacon
--     for inbound catalog events (AC #14): same event redelivering hits the constraint and
--     the listener catches DataIntegrityViolationException as a no-op (NFR-IDEM-1).
--
-- YAGNI / ponytail notes (intentional exclusions, documented here for future review):
--   * NO `on_hand` column on inventory_ledger — sum-derivation only.
--   * NO tenant_id on `outbox` or `processed_event` — event routing metadata, not business.
--   * NO indexes on `delta` or `reason` — selectivity is poor in v1 single-warehouse.
--   * NO FK on variant_id (cross-service: catalog_db.products is in a separate database).
--   * NO V002 in Story 1.5 — signatures JSONB on outbox is a Story 1.5+ follow-up
--     (the producer-side signing lives in catalog's V003). This service's outbound events
--     are unsigned in Story 1.5; signing lands with the lifecycle-events story.
--
-- RootEntity legacy `id` column:
--   util/BaseEntity → util/RootEntity declares `@Column(name = "id", nullable = false,
--   insertable = false, updatable = false)`. Hibernate's `validate` mode fails at boot when
--   the column is missing. We add a nullable `id BIGINT` to satisfy the validator; the
--   `insertable=false updatable=false` annotation means Hibernate never writes or reads it,
--   so the column stays NULL in practice. Catalog uses the same pattern (V002 back-fill
--   line 41). We include it here in V001 because the entity lands in this story (unlike
--   catalog's Story 1.1 placeholder).
--
-- InventoryLedger PK choice:
--   The spec's `id BIGSERIAL PRIMARY KEY` was renamed to `uuid` PRIMARY KEY for consistency
--   with util's BaseEntity contract (`@Id Long uuid`). The append-only property is preserved
--   via the application-layer convention (no delete methods on the repository; ArchUnit
--   boundary test). Postgres-BIGSERIAL row sequences are not needed for the ledger — the
--   business key is the Snowflake `uuid`.

-- =========================================================================
-- warehouses — list of warehouses (single-warehouse v1 per ADR-06; multi-warehouse by FR-10)
-- =========================================================================
CREATE TABLE warehouses (
    uuid         BIGINT       PRIMARY KEY,                 -- Snowflake ID from BaseEntity
    id           BIGINT,                                  -- RootEntity legacy id (insertable=false; nullable)
    code         VARCHAR(64)  NOT NULL UNIQUE,             -- 'HCM-01', 'HN-01' etc.
    display_name VARCHAR(255) NOT NULL,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by   VARCHAR(36),
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by   VARCHAR(36),
    updated_at   TIMESTAMP,
    deleted_by   VARCHAR(36),
    deleted_at   TIMESTAMP,
    -- tenant_id on day one (architecture-detail.md line 78 v1 single-tenant default)
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'default'
);

-- =========================================================================
-- inventory_ledger — the SOLE source of truth (FR-8, FR-13)
-- =========================================================================
-- on_hand is NOT a column; it's:
--     SELECT COALESCE(SUM(delta), 0) FROM inventory_ledger
--     WHERE variant_id = ? AND warehouse_id = ?
-- The READ side is a Postgres VIEW (V002) or computed in JPA (see OnHandUseCase).
-- NO `on_hand` mutation path exists.
CREATE TABLE inventory_ledger (
    uuid          BIGINT       PRIMARY KEY,                -- Snowflake ID (BaseEntity)
    id            BIGINT,                                 -- RootEntity legacy id (insertable=false; nullable)
    variant_id    BIGINT       NOT NULL,                   -- Snowflake of the variant (no FK; cross-service)
    warehouse_id  BIGINT       NOT NULL REFERENCES warehouses(uuid),
    delta         BIGINT       NOT NULL,                   -- signed: +N inbound, -N reserved/allocated/shipped
    reason        VARCHAR(64)  NOT NULL,                   -- 'receive','adjust','reserve','release','allocate','ship'
    event_id      BIGINT       NOT NULL UNIQUE,            -- Snowflake ID; idempotency key (cross-aggregate dedup)
    tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'default',
    created_by    VARCHAR(36),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by    VARCHAR(36),
    updated_at    TIMESTAMP,
    deleted_by    VARCHAR(36),
    deleted_at    TIMESTAMP,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_inventory_ledger_variant ON inventory_ledger(variant_id);
CREATE INDEX idx_inventory_ledger_warehouse ON inventory_ledger(warehouse_id);
-- Composite supports Story 1.7 multi-warehouse breakdown queries.
CREATE INDEX idx_inventory_ledger_variant_warehouse ON inventory_ledger(variant_id, warehouse_id);
-- Architecture.md line 296: uq_<table>_<column> naming.
ALTER TABLE inventory_ledger ADD CONSTRAINT uq_inventory_ledger_event_id UNIQUE (event_id);

-- =========================================================================
-- outbox — ADR-14 line 297 (canonical column set)
-- =========================================================================
-- Column names match architecture.md line 297 verbatim — Modulith's outbox bridge
-- (configured in Story 1.5) expects this exact shape.
CREATE TABLE outbox (
    id                  BIGSERIAL    PRIMARY KEY,
    aggregate_type      VARCHAR(64)  NOT NULL,                -- 'InventoryLedger'
    aggregate_id        BIGINT       NOT NULL,                -- Snowflake ID
    event_type          VARCHAR(128) NOT NULL,                -- 'inventory.receive', 'inventory.adjust', etc.
    event_id            BIGINT       NOT NULL UNIQUE,         -- Snowflake ID; idempotency key
    payload             JSONB        NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    published_at        TIMESTAMP                            -- NULL until bridge acks
);
-- Partial index: bridge scans unpublished rows in chronological order.
CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate   ON outbox(aggregate_type, aggregate_id);

-- =========================================================================
-- processed_event — ADR-04 (consumer idempotency)
-- =========================================================================
-- Empty in Story 1.5 (no consumers yet outside the in-process Modulith listener). The table MUST
-- exist now so future cross-process consumers can INSERT ... ON CONFLICT DO NOTHING on event_id.
CREATE TABLE processed_event (
    id                  BIGSERIAL    PRIMARY KEY,
    event_id            BIGINT       NOT NULL UNIQUE,         -- Snowflake ID of consumed event
    event_type          VARCHAR(128) NOT NULL,                -- for diagnostics
    processed_at        TIMESTAMP    NOT NULL DEFAULT now(),
    consumer            VARCHAR(128) NOT NULL                 -- e.g. 'inventory.CatalogEventListener'
);
CREATE INDEX idx_processed_event_consumer ON processed_event(consumer, processed_at);