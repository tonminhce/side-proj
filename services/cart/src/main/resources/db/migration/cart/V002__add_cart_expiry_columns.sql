-- V002__add_cart_expiry_columns.sql — Story 2.2 / FR-18 (cart auto-expire, ADR-04, ADR-14)
-- Adds the TTL anchor + a sweeper-friendly partial index. Mirrors Story 1.6's
-- inventory_reservation.expires_at precedent (V003__create_inventory_reservation.sql line 18):
-- the TTL is anchored at creation, NOT computed from updated_at + N days.
--
-- Why a partial index? The sweeper query is `WHERE status IN ('ANONYMOUS','ACTIVE') AND
-- expires_at < now()`. A partial index restricted to those statuses keeps the sweeper O(rows-to-expire)
-- instead of O(all-carts) and excludes the terminal MERGED / ABANDONED / CHECKED_OUT rows that should
-- never re-expire. The DB default (now() + INTERVAL '30 days') owns the initial TTL; the
-- application never computes `now() + 30 days` on insert.
ALTER TABLE carts ADD COLUMN expires_at TIMESTAMP NOT NULL DEFAULT (now() + INTERVAL '30 days');
CREATE INDEX idx_carts_status_expires_at ON carts(status, expires_at)
    WHERE status IN ('ANONYMOUS','ACTIVE');