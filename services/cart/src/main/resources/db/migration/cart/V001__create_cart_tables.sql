-- V001__create_cart_tables.sql — Story 2.1 (FR-14, FR-15, FR-16, NFR-IDEM-3; ADR-03, ADR-11, ADR-14)
-- CartService canonical tables. Flyway is the schema authority; JPA is `ddl-auto: validate` so any
-- entity-table mismatch fails fast at boot.
--
-- Architectural references:
--   * ADR-03 (database-per-service): cart_db is the only DB this service touches. cart_lines.variant_id
--     is a cross-service reference to catalog_db.products — NO FK (separate database).
--   * ADR-11 (idempotency-key strategy): cart_merge_log.idempotency_key = sha256(guestCartId + ":" + userId).
--     The UNIQUE constraint is the DB-level idempotency beacon for the merge endpoint (NFR-IDEM-3).
--   * ADR-14 (per-service outbox): the `outbox` + `processed_event` shape mirrors inventory's V001 verbatim.
--
-- RootEntity legacy `id` column: util/RootEntity declares
--   @Column(name="id", insertable=false, updatable=false). Hibernate `validate` fails if the column is
--   missing; we add a nullable `id BIGINT` (Hibernate never reads/writes it). Mirrors inventory V001.

-- =========================================================================
-- carts — the cart aggregate root (FR-14)
-- =========================================================================
CREATE TABLE carts (
    uuid          BIGINT       PRIMARY KEY,                 -- Snowflake ID from BaseEntity
    id            BIGINT,                                   -- RootEntity legacy id (insertable=false; nullable)
    tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'default',
    guest_cart_id VARCHAR(64),                              -- cookie UUID (anonymous carts); NULL for user-bound
    user_id       VARCHAR(64),                              -- auth user id (user-bound carts); NULL for anonymous
    status        VARCHAR(32)  NOT NULL DEFAULT 'ANONYMOUS'
        CHECK (status IN ('ANONYMOUS','ACTIVE','MERGED','ABANDONED','CHECKED_OUT')),
    version       BIGINT       NOT NULL DEFAULT 0,          -- optimistic concurrency (@Version)
    created_by    VARCHAR(36),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by    VARCHAR(36),
    updated_at    TIMESTAMP,
    deleted_by    VARCHAR(36),
    deleted_at    TIMESTAMP,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);
-- Partial unique indexes: anonymous carts have a guest_cart_id (user_id NULL); user-bound carts have
-- a user_id (guest_cart_id NULL). Two partials avoid the cross-nullable comparison issue (Postgres
-- treats NULL as distinct in a standard UNIQUE).
CREATE UNIQUE INDEX uq_carts_tenant_guest ON carts (tenant_id, guest_cart_id) WHERE guest_cart_id IS NOT NULL;
CREATE UNIQUE INDEX uq_carts_tenant_user  ON carts (tenant_id, user_id)       WHERE user_id IS NOT NULL;

-- =========================================================================
-- cart_lines — one row per (cart, variant) (FR-15)
-- =========================================================================
CREATE TABLE cart_lines (
    uuid        BIGINT       PRIMARY KEY,                   -- Snowflake ID
    id          BIGINT,                                     -- RootEntity legacy id
    cart_uuid   BIGINT       NOT NULL REFERENCES carts(uuid),  -- same-service FK
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'default',
    seller_id   VARCHAR(64),                                -- ADR-07 marketplace v2 placeholder (NULL in v1)
    variant_id  BIGINT       NOT NULL,                      -- cross-service (NO FK to catalog)
    quantity    INTEGER      NOT NULL CHECK (quantity > 0),
    version     BIGINT       NOT NULL DEFAULT 0,
    created_by  VARCHAR(36),
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by  VARCHAR(36),
    updated_at  TIMESTAMP,
    deleted_by  VARCHAR(36),
    deleted_at  TIMESTAMP,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_cart_lines_cart_variant UNIQUE (cart_uuid, variant_id)   -- merge "sum quantities" relies on this
);
CREATE INDEX idx_cart_lines_cart    ON cart_lines(cart_uuid);
CREATE INDEX idx_cart_lines_variant ON cart_lines(variant_id);

-- =========================================================================
-- cart_merge_log — append-only audit trail + idempotency beacon (NFR-IDEM-3, ADR-11)
-- =========================================================================
CREATE TABLE cart_merge_log (
    uuid              BIGINT       PRIMARY KEY,             -- Snowflake ID
    id                BIGINT,                               -- RootEntity legacy id
    tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'default',
    idempotency_key   VARCHAR(128) NOT NULL,               -- sha256 hex (64 chars); VARCHAR(128) buffer
    guest_cart_id     VARCHAR(64)  NOT NULL,
    user_id           VARCHAR(64)  NOT NULL,
    source_cart_uuid  BIGINT,                              -- the anonymous cart that was merged
    target_cart_uuid  BIGINT,                              -- the user-bound cart that received lines
    merged_lines_count INTEGER     NOT NULL DEFAULT 0,
    merged_at         TIMESTAMP    NOT NULL DEFAULT now(),
    created_by        VARCHAR(36),
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by        VARCHAR(36),
    updated_at        TIMESTAMP,
    deleted_by        VARCHAR(36),
    deleted_at        TIMESTAMP,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted        BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_cart_merge_log_idempotency_key UNIQUE (idempotency_key)
);
CREATE INDEX idx_cart_merge_log_user ON cart_merge_log(user_id, merged_at DESC);

-- =========================================================================
-- outbox — ADR-14 (canonical column set; mirrors inventory V001 + V004 signatures column)
-- =========================================================================
CREATE TABLE outbox (
    id                  BIGSERIAL    PRIMARY KEY,
    aggregate_type      VARCHAR(64)  NOT NULL,                -- 'Cart'
    aggregate_id        BIGINT       NOT NULL,                -- Snowflake ID
    event_type          VARCHAR(128) NOT NULL,                -- 'cart.merged'
    event_id            BIGINT       NOT NULL UNIQUE,          -- Snowflake ID; idempotency key
    payload             JSONB        NOT NULL,
    signatures          JSONB,                                -- ADR-20 HMAC map {"hmac_sha256": "..."}
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    published_at        TIMESTAMP                             -- NULL until bridge acks
);
CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate   ON outbox(aggregate_type, aggregate_id);

-- =========================================================================
-- processed_event — ADR-04 (consumer idempotency; empty in Story 2.1)
-- =========================================================================
CREATE TABLE processed_event (
    id                  BIGSERIAL    PRIMARY KEY,
    event_id            BIGINT       NOT NULL UNIQUE,          -- Snowflake ID of consumed event
    event_type          VARCHAR(128) NOT NULL,
    processed_at        TIMESTAMP    NOT NULL DEFAULT now(),
    consumer            VARCHAR(128) NOT NULL
);
CREATE INDEX idx_processed_event_consumer ON processed_event(consumer, processed_at);
