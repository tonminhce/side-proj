-- V001__create_users_table.sql — Story 5.4 / FR-73, FR-75, FR-76
-- User aggregate. The `customer_id` column is a BIGINT without FK — the auth service doesn't
-- have visibility into customer_db (per ADR-03). A future migration adds the FK once the
-- Modulith outbox wires the user.registered → customer.create event.

CREATE TABLE users (
    id                BIGSERIAL PRIMARY KEY,
    email             VARCHAR(255) UNIQUE NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    role              VARCHAR(16) NOT NULL DEFAULT 'user',
    mfa_enrolled      BOOLEAN     NOT NULL DEFAULT false,
    mfa_secret        VARCHAR(64),
    failed_attempts   INT         NOT NULL DEFAULT 0,
    locked_until      TIMESTAMP,
    customer_id       BIGINT,
    email_verified    BOOLEAN     NOT NULL DEFAULT false,
    created_at        TIMESTAMP   NOT NULL DEFAULT now(),
    last_login_at     TIMESTAMP
);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_customer_id ON users(customer_id);