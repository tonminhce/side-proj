-- 03-create-cart-db.sql — Story 2.1
-- Runs ONCE on first Postgres start (after 02-create-inventory-db.sql; postgres:16-alpine
-- auto-executes /docker-entrypoint-initdb.d/*.sql sorted by filename). Destroy the pg-data
-- volume to recreate (see dev/README.md).

-- CartService role + database (ADR-03: database-per-service).
CREATE ROLE cart_user WITH LOGIN PASSWORD 'cart_pass';
CREATE DATABASE cart_db OWNER cart_user;
GRANT ALL PRIVILEGES ON DATABASE cart_db TO cart_user;
-- cart_user owns cart_db; the dev `postgres` superuser is the bootstrap account.
-- Dev-only credentials. Prod secrets live in Vault per ADR-21.
