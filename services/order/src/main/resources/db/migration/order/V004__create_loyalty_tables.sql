-- V004__create_loyalty_tables.sql — Story 5.6 / FR-50
-- Loyalty points: per-userId account + per-order accrual record. The schema is forward-compatible
-- with the future redemption flow (a separate loyalty_redemption table lands with Story 5.6 follow-up
-- or the checkout UI story).

CREATE TABLE loyalty_account (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL UNIQUE,
    points      BIGINT NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_loyalty_account_customer_id ON loyalty_account(customer_id);

CREATE TABLE loyalty_accrual (
    id          BIGSERIAL PRIMARY KEY,
    order_uuid  BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    points      INT    NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (order_uuid)
);
CREATE INDEX idx_loyalty_accrual_customer_id ON loyalty_accrual(customer_id);