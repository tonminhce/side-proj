# 20 — Runtime Smoke Tests

> What to curl, what to look for, and what counts as "this service is real."

## TL;DR

After starting 4 services (catalog, inventory, cart, payment), the smoke test is:

```bash
for port in 8091 8093 8095 8096; do
  curl -sS -m 5 http://localhost:$port/actuator/health | jq -c .
done
```

Each should return: `{"status":"UP","db":{...},"components":{...}}` with `db.status=UP`. That's the **only** smoke test that works out-of-the-box for all 4 services today.

## Per-service smoke recipes

### 1. Catalog (port 8091)

```bash
# Health
curl -sS http://localhost:8091/actuator/health | jq .
# Expected: components.db.status=UP, components.redis.status=UP

# DB has Flyway-applied schema
docker exec ecommerce-platform-postgres-1 psql -U postgres -d catalog_db -c "\dt"
# Expected tables: attributes, flyway_schema_history, outbox, processed_event, products, variants

# API endpoints require JWT auth in services that have spring-security on classpath.
# Even DevSecurityConfig doesn't always win the bean-order race. Use DB peek instead.
```

### 2. Inventory (port 8093)

```bash
# Health
curl -sS http://localhost:8093/actuator/health | jq .

# DB check (Flyway runs V001-V004 on first boot)
docker exec ecommerce-platform-postgres-1 psql -U postgres -d inventory_db -c "\dt"
# Expected: flyway_schema_history, inventory_ledger_entry, inventory_reservation, warehouses, ...

# Note: `inventory.lifecycle` topic events emitted by Story 1.5+ only after a saga runs.
```

### 3. Cart (port 8095)

```bash
# Health
curl -sS http://localhost:8095/actuator/health | jq .

# DB check
docker exec ecommerce-platform-postgres-1 psql -U postgres -d cart_db -c "\dt"
# Expected: cart, cart_line, cart_merge_log, flyway_schema_history, outbox
```

### 4. Payment (port 8096) — has the FRESHEST code (Story 3.2 Stripe webhook dedup)

```bash
# Health
curl -sS http://localhost:8096/actuator/health | jq .

# DB check (Flyway-applied)
docker exec ecommerce-platform-postgres-1 psql -U postgres -d payment_db -c "\dt"
# Expected: webhook_dedup, payment_intent, payment_idempotency_key, flyway_schema_history, outbox

# Webhook dedup verification (DB-only — webhook endpoint returns 401/403 due to security
# config; verify the dedup table directly):
docker exec ecommerce-platform-postgres-1 psql -U postgres -d payment_db \
  -c "SELECT event_id, count(*) FROM webhook_dedup GROUP BY event_id ORDER BY 1 DESC LIMIT 5"
# Expected: each event_id appears once (dedup is happening)
```

## Quick smoke script (4 services)

```bash
cd /Users/tonminh-mac/IdeaProjects/side-proj

# 1. Health
for port in 8091 8093 8095 8096; do
  echo "=== :$port ==="
  curl -sS -m 5 http://localhost:$port/actuator/health | jq -c .
done

# 2. DB sanity (Flyway ran?)
for db in catalog_db inventory_db cart_db payment_db; do
  echo "=== $db ==="
  docker exec ecommerce-platform-postgres-1 psql -U postgres -d $db -tAc \
    "SELECT count(*) || ' tables, schema history: ' || max(version) FROM information_schema.tables, flyway_schema_history" 2>/dev/null
done

# 3. Redis
docker exec ecommerce-platform-redis-1 redis-cli ping
# Expected: PONG
```

## What success looks like

- 4 services UP
- 5 service DBs created with tables
- Redis ping returns PONG
- Kafka topics auto-created (check via `kafka-topics --list` if you have Kafka CLI; otherwise skip)

## What failure looks like — common issues

See [60-troubleshoot.md](60-troubleshoot.md).

## Next

- [30-known-issues.md](30-known-issues.md) — documented bugs (F1–F17 + post-orchestrator) — for context on what doesn't work
- [40-verify-curl.md](40-verify-curl.md) — per-service curl cookbook (where auth works)
- [50-services-overview.md](50-services-overview.md) — what each service does
