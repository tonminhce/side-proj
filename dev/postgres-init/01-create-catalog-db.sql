-- 01-create-catalog-db.sql — Story 1.1
-- Runs ONCE on first Postgres start (when the pg-data volume is empty). postgres:16-alpine
-- auto-executes everything in /docker-entrypoint-initdb.d/*.sql sorted by filename. On
-- subsequent starts the script is NOT re-run; destroy the pg-data volume to recreate
-- (see dev/README.md).

-- CatalogService role + database (ADR-03: database-per-service).
CREATE ROLE catalog_user WITH LOGIN PASSWORD 'catalog_pass';
CREATE DATABASE catalog_db OWNER catalog_user;
GRANT ALL PRIVILEGES ON DATABASE catalog_db TO catalog_user;
-- catalog_user owns catalog_db; the dev `postgres` superuser is the bootstrap account.
-- Cross-DB reads from `postgres` (the dev superuser) are possible by virtue of SUPERUSER,
-- but no FK crosses the database boundary — verified in V001__create_catalog_tables.sql.
-- Dev-only credentials. Prod secrets live in Vault per ADR-21.