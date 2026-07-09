-- V008 — Epic 5 polish sweep.
-- Widen loyalty_accrual.points from INT to BIGINT to prevent theoretical 32-bit overflow
-- (~21M points per order, realistic totals < 10M VND but BIGINT is cheap insurance).
-- Existing rows convert cleanly (no data loss).

ALTER TABLE loyalty_accrual ALTER COLUMN points TYPE BIGINT;