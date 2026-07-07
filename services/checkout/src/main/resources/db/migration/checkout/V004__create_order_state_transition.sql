-- V004__create_order_state_transition.sql — Story 2.5 / FR-22, FR-23 (ADR-12 / NFR-OBS-3)
-- Append-only transition log. Each saga transition appends one row; UPDATE / DELETE are forbidden
-- by application convention (the repository exposes only `save` + `findByOrderUuidOrderByCreatedAtAsc`).
--
-- Architectural references:
--   * ADR-12 — saga = single Modulith module, intra-process; transition log is the saga's
--     replay source on crash-restart.
--   * NFR-OBS-3 — every state transition is observable (the log + saga transition metrics).
--   * `event_id` UNIQUE — append-only invariant enforced at the DB level (BaseEntity.@PrePersist
--     assigns the Snowflake id; duplicates signal a buggy writer).
--
-- DO NOT amend V001..V003 (Flyway = immutable applied migrations).

-- =========================================================================
-- order_state_transition — append-only saga transition log
-- =========================================================================
CREATE TABLE order_state_transition (
    uuid                BIGINT       PRIMARY KEY,                 -- Snowflake ID from BaseEntity
    id                  BIGINT,                                   -- RootEntity legacy id (insertable=false; nullable)
    order_uuid          BIGINT       NOT NULL,                    -- saga co-located ref (no JPA FK)
    from_state          VARCHAR(32),                              -- NULL for the initial CREATED row
    to_state            VARCHAR(32)  NOT NULL,
    saga_step           VARCHAR(64)  NOT NULL,                    -- e.g. 'cart.submit', 'stock.reserve'
    event_id            BIGINT       NOT NULL UNIQUE,             -- Snowflake; append-only invariant
    failure_reason      VARCHAR(128),                             -- NULL on success rows
    created_by          VARCHAR(36),
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by          VARCHAR(36),
    updated_at          TIMESTAMP,
    deleted_by          VARCHAR(36),
    deleted_at          TIMESTAMP,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE
);
-- Index that backs the recovery runner's `findByOrderUuidOrderByCreatedAtAsc(uuid)`.
CREATE INDEX idx_order_state_transition_order ON order_state_transition(order_uuid, created_at);