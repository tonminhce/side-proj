-- V004__add_signatures_to_outbox.sql — Story 1.6 (closes ADR-20 producer half for inventory)
-- Mirrors catalog's V003 (Story 1.3) — the outbox.signatures JSONB column + partial unsigned
-- index for cross-process Kafka consumers (Story 10.x).
ALTER TABLE outbox ADD COLUMN signatures JSONB;
CREATE INDEX idx_outbox_unsigned ON outbox(created_at)
    WHERE published_at IS NULL AND signatures IS NULL;