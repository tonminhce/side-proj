-- V002__add_tenant_id.sql — Story 1.2 (deferred from V001 per architecture-detail.md line 78)
-- Two changes: (1) add tenant_id per AC #9; (2) complete the variants/attributes skeleton from V001
-- so the entities in Story 1.2 (which inherit audit fields from util/BaseEntity) survive
-- `spring.jpa.hibernate.ddl-auto=validate`.
--
-- Why V002 also back-fills audit columns:
--   * V001 intentionally shipped skeletal `variants` and `attributes` tables (V001 header: "Story 1.2
--     will map entities to these tables and add the columns the aggregate needs"). Many audit
--     columns that BaseEntity / RootEntity declare (@Column `created_by`, `updated_by`, `deleted_by`,
--     `deleted_at`, `id`, `is_active`) are missing from V001's variants/attributes DDL.
--   * Hibernate's `validate` mode fails at boot when entity-referenced columns are missing; the
--     spec's "Story 1.1 → 1.2 gate flip" is a no-go unless the schema is complete.
--   * The back-fill is additive (nullable timestamp/audit columns; `tenant_id` has a DEFAULT so
--     existing rows pass).
--   * This is the cleanest place to land the back-fill: the same migration also adds tenant_id,
--     so the next boot sees one forward step, not two.
--
-- Architectural references:
--   * architecture-detail.md line 78: tenant_id on every per-service table; 'default' in v1.
--   * architecture-detail.md line 97: Lombok + audit-field inheritance from util/BaseEntity.
--
-- YAGNI / ponytail:
--   * NO tenant_id on `outbox` / `processed_event` (event routing metadata, not business).
--   * NO index on tenant_id in v1 (selectivity is 1.0; dead weight). Story 5.x adds partial index.
--   * NO new util/BaseEntity fields — keep the inherited audit contract stable.

-- =========================================================================
-- 1. tenant_id — AC #9
-- =========================================================================
ALTER TABLE products   ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE variants   ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE attributes ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

-- =========================================================================
-- 2. Audit columns that BaseEntity / RootEntity require (inherited via Lombok)
-- =========================================================================
-- `id` column: RootEntity declares it `nullable=false insertable=false updatable=false`. Postgres
-- sees nullable (no DEFAULT, no NOT NULL) — Hibernate's `insertable=false` skips INSERT so the
-- column stays NULL. Entity `@AttributeOverride` on `id` drops the inherited `nullable=false`
-- so `validate` accepts the schema mismatch.
ALTER TABLE products   ADD COLUMN id          BIGINT;
ALTER TABLE variants   ADD COLUMN id          BIGINT;
ALTER TABLE attributes ADD COLUMN id          BIGINT;

-- `created_by`, `updated_by`, `deleted_by`: RootEntity columns the V001 skeleton omitted.
ALTER TABLE variants   ADD COLUMN created_by  VARCHAR(36);
ALTER TABLE variants   ADD COLUMN updated_by  VARCHAR(36);
ALTER TABLE variants   ADD COLUMN deleted_by  VARCHAR(36);
ALTER TABLE attributes ADD COLUMN created_by  VARCHAR(36);
ALTER TABLE attributes ADD COLUMN updated_by  VARCHAR(36);
ALTER TABLE attributes ADD COLUMN deleted_by  VARCHAR(36);

-- `deleted_at`: nullable timestamp.
ALTER TABLE variants   ADD COLUMN deleted_at  TIMESTAMP;
ALTER TABLE attributes ADD COLUMN deleted_at  TIMESTAMP;

-- `is_active` / `updated_at` on attributes for symmetry with variants.
ALTER TABLE attributes ADD COLUMN is_active   BOOLEAN      NOT NULL DEFAULT TRUE;
ALTER TABLE attributes ADD COLUMN updated_at  TIMESTAMP;
