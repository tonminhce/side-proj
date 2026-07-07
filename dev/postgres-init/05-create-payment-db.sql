-- 05-create-payment-db.sql — Story 3.1 (FR-25, ADR-03)
-- Runs ONCE on first Postgres start (after 04-create-checkout-db.sql; postgres:16-alpine
-- auto-executes /docker-entrypoint-initdb.d/*.sql sorted by filename). Destroy the pg-data
-- volume to recreate (see dev/README.md).

-- PaymentService role + database (ADR-03: database-per-service).
CREATE ROLE payment_user WITH LOGIN PASSWORD 'payment_pass';
CREATE DATABASE payment_db OWNER payment_user;
GRANT ALL PRIVILEGES ON DATABASE payment_db TO payment_user;
-- payment_user owns payment_db; the dev `postgres` superuser is the bootstrap account.
-- Dev-only credentials. Prod secrets live in Vault per ADR-21.