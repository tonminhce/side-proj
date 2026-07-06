# Dev Platform — Story 0.3

`docker compose up -d` brings up the local dev infrastructure: **Postgres + Kafka (KRaft) + Elasticsearch + Redis + Apicurio + MinIO + OPA**. Application services (`catalog`, `inventory`, ...) are NOT in this file — they ship with their Epic stories.

Expected time-to-healthy: **~60 s** on a warm cache.

## Service URLs

| Service        | Host port | Notes                                                    |
|----------------|-----------|----------------------------------------------------------|
| Postgres       | `5432`    | superuser `postgres`, db `app`, pwd `postgres` (dev only) |
| Postgres `catalog_db` | `5432` | per-service DB for CatalogService (Story 1.1) — user `catalog_user` / pwd `catalog_pass`; JDBC `jdbc:postgresql://localhost:5432/catalog_db` |
| Postgres `inventory_db` | `5432` | per-service DB for InventoryService (Story 1.5) — user `inventory_user` / pwd `inventory_pass`; JDBC `jdbc:postgresql://localhost:5432/inventory_db` |
| Kafka          | `9092`    | KRaft, single-node, internal listeners + PLAINTEXT host  |
| Elasticsearch  | `9200`    | single-node (8.15.0); Vietnamese analyzer is application-layer (Story 6.2) |
| Redis          | `6379`    | `maxmemory-policy allkeys-lru`                           |
| Apicurio       | `8081`    | 2.6 in-memory, Kafka-backed                              |
| MinIO S3 API   | `9000`    | creds `minio` / `minio123` (dev only)                    |
| MinIO console  | `9001`    | browser UI                                               |
| OPA            | `8181`    | ADR-19 admission policy loaded from `/policies`          |

## Quickstart

```bash
# 1. Bring everything up
docker compose -f dev/docker-compose.yml up -d

# 2. Wait ~60s for healthchecks, then smoke-test
bash dev/scripts/smoke.sh

# 3. Tear down (keeps volumes for fast next start)
docker compose -f dev/docker-compose.yml down

# 4. Nuclear reset (drops volumes, fresh data)
docker compose -f dev/docker-compose.yml down -v
```

`dev/.env.example` is committed; `dev/.env` is gitignored and used for any local overrides. Do NOT commit real secrets — the defaults are dev-only.

## Schema-registry note (Apicurio in-memory)

The `apicurio-registry-mem` variant stores schema artifacts on a Kafka topic. `docker compose down -v` (volume wipe) **will delete all registered schemas**. Acceptable for Sprint 0; Story 0.4 / production moves to the Postgres-backed `apicurio-registry` image.

## OPA policies

OPA loads `platform/policies/opa/*.rego` at startup. The current set:

- `kafka-topic-creation.rego` — rejects Kafka topics without declared retention (ADR-19 / NFR-SEC-3).
- `schema-registration.rego` — stub, expanded in Story 10.3.

Evaluate a sample input:

```bash
docker compose -f dev/docker-compose.yml exec opa \
  opa eval -d /policies -i - < input.json 'data.kafka.admission.deny'
```

## Troubleshoot

```bash
docker compose -f dev/docker-compose.yml ps                    # state of every service
docker compose -f dev/docker-compose.yml logs -f <service>     # follow logs for one service
docker compose -f dev/docker-compose.yml restart <service>     # restart without dropping data
```

A common startup hiccup: Kafka KRaft takes ~30 s to elect itself; the healthcheck absorbs this via `start_period: 30s`. If `kafka` shows `health: starting`, give it another 20–30 s.

## Per-service databases (ADR-03)

`dev/postgres-init/` runs once on first Postgres start (when `pg-data` is empty). It creates the role + database for each service that has shipped its bootstrap story:

- `catalog_db` (Story 1.1) — owner `catalog_user` / pwd `catalog_pass`.
- `inventory_db` (Story 1.5) — owner `inventory_user` / pwd `inventory_pass`.

On subsequent starts the init scripts do NOT re-run; destroying the `pg-data` volume (`docker compose down -v`) recreates everything from scratch. To recreate a single service's database without wiping the others, connect as the `postgres` superuser and `DROP DATABASE` + re-run the matching `dev/postgres-init/*.sql` snippet manually.

## Read admin view (Story 1.4 / FR-6)

The admin catalog read view is a 3-tier path: `frontend/admin/` (Next.js 15) → `bff/admin-bff/` (Spring Boot 4 proxy on `:8082`) → `services/catalog/` (data on `:8081`).

Bring it up:

```bash
# 1. catalog service (Spring Boot jar — built from services/catalog/target/)
java -jar services/catalog/target/catalog-1.0-SNAPSHOT.jar

# 2. admin-bff (Spring Boot jar — built from bff/admin-bff/target/)
java -jar bff/admin-bff/target/admin-bff-1.0-SNAPSHOT.jar

# 3. admin frontend
cd frontend/admin && npm run dev   # http://localhost:3001/admin/catalog
```

Browser smoke: `curl -H "X-User-Roles: staff" http://localhost:8082/bff/admin/catalog/products?page=0&size=20` should return `200 OK` with a `content` array.

The `X-User-Roles` header is the **dev/test placeholder** (Story 5.5 replaces it with util's `CustomSecurityExpressionHandler` reading JWT roles). Production deploys MUST NOT have the header — `@Profile({"dev","test"})` on `DevRolesHeaderFilter` excludes the bean in the `prod` profile.

## What this file does NOT contain

- No application services (those ship with Epic 1+).
- No Debezium Connect (Story 1.3).
- No observability stack (Prometheus/Grafana/etc.) — out of scope for Sprint 0.
- No TLS, no mTLS, no Vault — dev-only credentials; prod uses a real secret manager.
