-- V005__add_region_to_warehouses.sql — Story 1.7 / FR-10 (ADR-06 multi-warehouse stretch)
-- Adds the routing key for "closest warehouse to shipping address" reservation dispatch
-- (FR-10 binding: R-02 mitigation in brainstorming). v1 single-warehouse had no region
-- because there was only 1 warehouse to route to. Story 1.7 lifts the ADR-06 stretch
-- constraint and makes region a first-class routing attribute.
--
-- Architecture: per architecture.md line 215 (ADR-06) + architecture-detail.md line 78
-- (tenant_id v1 single-tenant default). region follows the same single-tenant default
-- convention: a hardcoded enum-like VARCHAR(16) (NORTH | SOUTH | CENTRAL) for v1.

ALTER TABLE warehouses
    ADD COLUMN region VARCHAR(16) NOT NULL DEFAULT 'SOUTH';
-- Backfill: all existing rows (e.g., the HCM-01 seeded by Story 1.5's CatalogEventListener
-- defaultWarehouseId()) default to 'SOUTH'. The default is dropped below after backfill;
-- future inserts MUST supply a region explicitly (the CHECK constraint enforces).

ALTER TABLE warehouses
    ALTER COLUMN region DROP DEFAULT;

ALTER TABLE warehouses
    ADD CONSTRAINT chk_warehouses_region
        CHECK (region IN ('NORTH', 'SOUTH', 'CENTRAL'));

-- Seed two real warehouses for FR-10 dispatch.
-- uuid is Snowflake Long — values below are dev seed IDs only (production uses
-- SnowflakeIdGenerator.generateId()). The seed runs once on Flyway apply; idempotent
-- IF NOT EXISTS via uq_warehouses_code (Story 1.5's V001 convention).
--
-- PONYTAIL: uq_warehouses_code is the deduplication key; ON CONFLICT DO NOTHING keeps
-- re-applying V005 a no-op (Flyway checksum prevents re-apply, but this guards against
-- dev re-seeds).
INSERT INTO warehouses (uuid, id, code, display_name, region, tenant_id, created_at, is_active, is_deleted)
VALUES (1001, NULL, 'HCM-01', 'Ho Chi Minh Central', 'SOUTH', 'default', now(), TRUE, FALSE)
    ON CONFLICT (code) DO NOTHING;

INSERT INTO warehouses (uuid, id, code, display_name, region, tenant_id, created_at, is_active, is_deleted)
VALUES (1002, NULL, 'HN-01', 'Hanoi Central', 'NORTH', 'default', now(), TRUE, FALSE)
    ON CONFLICT (code) DO NOTHING;