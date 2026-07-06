# Dev Platform — Story 0.3

`docker compose up -d` brings up the local dev infrastructure: **Postgres + Kafka (KRaft) + Elasticsearch + Redis + Apicurio + MinIO + OPA**. Application services (`catalog`, `inventory`, ...) are NOT in this file — they ship with their Epic stories.

Expected time-to-healthy: **~60 s** on a warm cache.

## Service URLs

| Service        | Host port | Notes                                                    |
|----------------|-----------|----------------------------------------------------------|
| Postgres       | `5432`    | user `postgres`, db `app`, pwd `postgres` (dev only)      |
| Kafka          | `9092`    | KRaft, single-node, internal listeners + PLAINTEXT host  |
| Elasticsearch  | `9200`    | single-node, `analysis-vn` plugin installed              |
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

## What this file does NOT contain

- No application services (those ship with Epic 1+).
- No Debezium Connect (Story 1.3).
- No observability stack (Prometheus/Grafana/etc.) — out of scope for Sprint 0.
- No TLS, no mTLS, no Vault — dev-only credentials; prod uses a real secret manager.
