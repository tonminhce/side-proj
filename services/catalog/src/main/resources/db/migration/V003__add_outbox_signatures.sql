-- V003__add_outbox_signatures.sql — Story 1.3 (HMAC event signing per ADR-20)
-- Adds the per-service signature JSONB column to the outbox table so consumers can
-- verify that a published event originated from the named service (AT-03 mitigation).
--
-- Nullable: rows from Story 1.2 / 1.3's first deploy have no signature; the consumer
-- falls back to "unsigned = log a warning" for NULL signatures (the bridge will start
-- signing from this story forward, so the gap is small in practice).
--
-- ADR-20 line 177-192: the signing service is the producer's own name; the consumer
-- looks up secret/events/hmac/<signing-service-name> and recomputes. The signing-
-- service-name lives in signatures.service of the envelope.

ALTER TABLE outbox ADD COLUMN signatures JSONB;

-- Partial index: a downstream scanner that audits signatures (Story 10.1 LGTM dashboard)
-- queries WHERE signatures IS NULL — make that fast.
CREATE INDEX idx_outbox_unsigned ON outbox(id) WHERE signatures IS NULL;
