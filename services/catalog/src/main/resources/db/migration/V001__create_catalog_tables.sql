-- V001__create_catalog_tables.sql — Story 1.1
-- CatalogService canonical tables. Schema is intentionally skeletal (DDL only; entities land in
-- Story 1.2). Flyway is the schema authority; JPA is configured with `ddl-auto: validate` so any
-- entity-table mismatch fails fast at boot.
--
-- Architectural references:
--   * ADR-03 (database-per-service): catalog_db is the only DB this service touches.
--   * ADR-14 (per-service outbox): the `outbox` table shape matches Modulith's outbox bridge
--     expectations (architecture.md line 297) — do not rename columns.
--   * ADR-04 (consumer idempotency): `processed_event.event_id` is the unique idempotency key.
--
-- YAGNI / ponytail notes:
--   * `products`, `variants`, `attributes` are DDL skeletons only. Story 1.2 will map entities to
--     these tables and add the columns the aggregate needs (e.g. price_cents on products — that
--     lives on `variants` today by design, see `epics.md` line 472).
--   * tenant_id is deferred to Story 1.2 (per architecture-detail.md line 78 + Subtask 4.7).
--     Pre-creating columns here would force a forward-port when entities arrive; cleaner to add
--     the column in the same migration that introduces the entity.

-- =========================================================================
-- products
-- =========================================================================
-- Aggregate root. uuid = Snowflake ID (BaseEntity.prePersist() generates it).
-- Audit fields mirror RootEntity (created_by/created_at/updated_by/updated_at/deleted_by/deleted_at).
CREATE TABLE products (
    uuid            BIGINT       PRIMARY KEY,                 -- Snowflake ID from BaseEntity
    name            VARCHAR(255) NOT NULL,
    sku             VARCHAR(64)  NOT NULL UNIQUE,
    description     TEXT,
    brand           VARCHAR(128),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by      VARCHAR(64),
    updated_at      TIMESTAMP,
    deleted_by      VARCHAR(64),
    deleted_at      TIMESTAMP
    -- tenant_id deferred to Story 1.2 (when Product entity lands) per architecture-detail.md line 78
);
CREATE INDEX idx_products_brand       ON products(brand);
-- Partial index: only list active, non-deleted products.
CREATE INDEX idx_products_is_active   ON products(is_active) WHERE is_deleted = FALSE;

-- =========================================================================
-- variants
-- =========================================================================
-- Sku hash(option1|option2) per Story 1.2. attributes JSONB holds per-variant selection;
-- `attributes` table below holds the canonical attribute *definitions* (admin-managed).
CREATE TABLE variants (
    uuid            BIGINT       PRIMARY KEY,                 -- Snowflake ID
    product_uuid    BIGINT       NOT NULL REFERENCES products(uuid),
    sku             VARCHAR(64)  NOT NULL UNIQUE,
    attributes      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    price_cents     BIGINT       NOT NULL,                    -- minor units (architecture.md line 457)
    currency        VARCHAR(3)   NOT NULL DEFAULT 'VND',
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP
);
CREATE INDEX idx_variants_product_uuid ON variants(product_uuid);

-- =========================================================================
-- attributes
-- =========================================================================
-- Canonical attribute *definitions* per product (e.g. color, size, material).
-- Distinct from `variants.attributes` JSONB (which is the per-variant selection).
CREATE TABLE attributes (
    uuid            BIGINT       PRIMARY KEY,
    product_uuid    BIGINT       NOT NULL REFERENCES products(uuid),
    name            VARCHAR(64)  NOT NULL,                    -- 'color', 'size', 'material'
    display_name    VARCHAR(128) NOT NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (product_uuid, name)
);

-- =========================================================================
-- outbox — ADR-14 line 297 (canonical column set)
-- =========================================================================
-- Column names match architecture.md line 297 verbatim — Modulith's outbox bridge
-- (wired in Story 1.3) expects this exact shape.
CREATE TABLE outbox (
    id                  BIGSERIAL    PRIMARY KEY,
    aggregate_type      VARCHAR(64)  NOT NULL,                -- 'Product', 'Variant', etc.
    aggregate_id        BIGINT       NOT NULL,                -- Snowflake ID
    event_type          VARCHAR(128) NOT NULL,                -- 'catalog.product.created'
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
-- Empty in Story 1.1 (no consumers yet — Story 1.3 wires the first listener). The table MUST
-- exist now so consumer code can INSERT … ON CONFLICT DO NOTHING on event_id.
CREATE TABLE processed_event (
    id                  BIGSERIAL    PRIMARY KEY,
    event_id            BIGINT       NOT NULL UNIQUE,         -- Snowflake ID of consumed event
    event_type          VARCHAR(128) NOT NULL,                -- for diagnostics
    processed_at        TIMESTAMP    NOT NULL DEFAULT now(),
    consumer            VARCHAR(128) NOT NULL                 -- e.g. 'catalog.ProductListener'
);
CREATE INDEX idx_processed_event_consumer ON processed_event(consumer, processed_at);