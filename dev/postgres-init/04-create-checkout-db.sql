-- 04-create-checkout-db.sql — Story 2.3
-- Runs ONCE on first Postgres start (after 03-create-cart-db.sql; postgres:16-alpine
-- auto-executes /docker-entrypoint-initdb.d/*.sql sorted by filename). Destroy the pg-data
-- volume to recreate (see dev/README.md).

-- CheckoutService role + database (ADR-03: database-per-service).
CREATE ROLE checkout_user WITH LOGIN PASSWORD 'checkout_pass';
CREATE DATABASE checkout_db OWNER checkout_user;
GRANT ALL PRIVILEGES ON DATABASE checkout_db TO checkout_user;
-- checkout_user owns checkout_db; the dev `postgres` superuser is the bootstrap account.
-- Dev-only credentials. Prod secrets live in Vault per ADR-21.