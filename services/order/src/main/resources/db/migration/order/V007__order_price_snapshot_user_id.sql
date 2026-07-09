-- V007 — Move A (snapshot.user_id).
-- Capture the authenticated userId on the immutable price snapshot at order placement
-- (PLACED transition). Nullable because existing snapshots have no userId; the saga skips
-- loyalty accrual on those (logged as a warning). In v1, snapshot.userId IS the customerId
-- (services/auth's users.customer_id BIGINT column equals userId; the saga passes userId
-- straight through to AccrueLoyaltyPointsUseCase.execute).
--
-- Forward-compat: a future migration can wire a user→customer cross-service lookup if
-- the auth/customer key model diverges.

ALTER TABLE order_price_snapshot ADD COLUMN user_id BIGINT;
CREATE INDEX idx_order_price_snapshot_user_id ON order_price_snapshot(user_id);