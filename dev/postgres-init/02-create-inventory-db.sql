-- 02-create-inventory-db.sql — Story 1.5
-- Runs ONCE on first Postgres start (after 01-create-catalog-db.sql, since postgres:16-alpine
-- auto-executes /docker-entrypoint-initdb.d/*.sql sorted by filename). On subsequent starts
-- the script is NOT re-run; destroy the pg-data volume to recreate (see dev/README.md).

-- InventoryService role + database (ADR-03: database-per-service).
CREATE ROLE inventory_user WITH LOGIN PASSWORD 'inventory_pass';
CREATE DATABASE inventory_db OWNER inventory_user;
GRANT ALL PRIVILEGES ON DATABASE inventory_db TO inventory_user;
-- inventory_user owns inventory_db; the dev `postgres` superuser is the bootstrap account.
-- Dev-only credentials. Prod secrets live in Vault per ADR-21.