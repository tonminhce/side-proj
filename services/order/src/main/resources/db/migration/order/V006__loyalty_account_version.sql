-- V006 — Move B (Loyalty hardening).
-- Add optimistic-lock version column to loyalty_account. Hibernate @Version
-- manages this transparently; existing rows are seeded with 0 (a fresh write
-- will increment to 1). Hibernate will reject any concurrent update where the
-- in-memory version doesn't match the row's current version. The
-- AccrueLoyaltyPointsUseCase catches ObjectOptimisticLockingFailureException
-- and retries once (idempotent because accrual UNIQUE(order_uuid) prevents
-- double-application).

ALTER TABLE loyalty_account ADD COLUMN version BIGINT NOT NULL DEFAULT 0;