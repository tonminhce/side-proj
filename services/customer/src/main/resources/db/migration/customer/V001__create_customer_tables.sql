-- V001__create_customer_tables.sql — Story 5.1 / FR-45, FR-47
-- Customer aggregate + Address book. Per ADR-03, the service owns customer_db; the auth service
-- (Story 5.4) will provide the auth.users table separately and a FK to customer.user_id is wired
-- by a future migration.

CREATE TABLE customer (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    email        VARCHAR(255),
    phone        VARCHAR(32),
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_user_id ON customer(user_id);

CREATE TABLE address (
    id            BIGSERIAL PRIMARY KEY,
    customer_id   BIGINT NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    line1         VARCHAR(512) NOT NULL,
    province_code VARCHAR(16) NOT NULL,
    district_code VARCHAR(16) NOT NULL,
    commune_code  VARCHAR(16) NOT NULL,
    is_default    BOOLEAN NOT NULL DEFAULT false,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_address_customer_id ON address(customer_id);