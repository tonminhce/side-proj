# 40 — Verify Curl Cookbook

> Per-service curl recipes. Each works against a running service. **Some return 401 — that's expected (see [30-known-issues.md](30-known-issues.md#issue-8)).**

## Conventions

- `BASE_<SVC>` = `http://localhost:<port>` for the service
- `<port>` per service: catalog 8091, inventory 8093, cart 8095, checkout 8094, payment 8096
- All curls return JSON. `jq` is your friend.
- Add `-H "X-Tenant: default"` for tenant-aware services (catalog, cart, checkout, payment).

---

## Catalog (8091)

```bash
BASE=http://localhost:8091

# 1. Health — should always work (Spring Boot whitelists /actuator)
curl -sS $BASE/actuator/health | jq .

# 2. List products (auth-blocked)
curl -sS -H "X-Tenant: default" $BASE/api/products -w "\n[HTTP %{http_code}]\n"

# 3. Direct DB — most useful for verifying
docker exec ecommerce-platform-postgres-1 psql -U postgres -d catalog_db -c "\dt"
docker exec ecommerce-platform-postgres-1 psql -U postgres -d catalog_db -c "SELECT count(*) FROM products"
docker exec ecommerce-platform-postgres-1 psql -U postgres -d catalog_db -c "SELECT * FROM products LIMIT 3"
```

Expected `/actuator/health`:
```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "PostgreSQL" } },
    "redis": { "status": "UP" },
    "ping": { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```

---

## Inventory (8093)

```bash
BASE=http://localhost:8093

# 1. Health
curl -sS $BASE/actuator/health | jq .

# 2. List warehouses (auth-blocked)
curl -sS -H "X-Tenant: default" $BASE/api/warehouses -w "\n[HTTP %{http_code}]\n"

# 3. Direct DB
docker exec ecommerce-platform-postgres-1 psql -U postgres -d inventory_db -c "\dt"
docker exec ecommerce-platform-postgres-1 psql -U postgres -d inventory_db -c "SELECT count(*) FROM inventory_ledger_entry"
docker exec ecommerce-platform-postgres-1 psql -U postgres -d inventory_db -c "SELECT region, code, name FROM warehouses"
```

---

## Cart (8095)

```bash
BASE=http://localhost:8095

# 1. Health
curl -sS $BASE/actuator/health | jq .

# 2. Create cart (auth-blocked by DevSecurityConfig not winning)
curl -sS -X POST -H "Content-Type: application/json" -H "X-Tenant: default" \
  $BASE/api/carts \
  -d '{"guestCartId":"smoke-test-001"}' -w "\n[HTTP %{http_code}]\n"

# 3. Direct DB
docker exec ecommerce-platform-postgres-1 psql -U postgres -d cart_db -c "\dt"
docker exec ecommerce-platform-postgres-1 psql -U postgres -d cart_db -c "SELECT count(*) FROM cart"
docker exec ecommerce-platform-postgres-1 psql -U postgres -d cart_db -c "SELECT * FROM cart LIMIT 3"
```

---

## Checkout (8094) — DOES NOT START

Checkout fails to boot. See [30-known-issues.md](30-known-issues.md#issue-2-checkout-saga-2-5-entity-scanning). Skip this for now.

To confirm: `curl -m 3 http://localhost:8094/actuator/health` returns `Failed to connect to localhost port 8094` because nothing is listening.

---

## Payment (8096)

```bash
BASE=http://localhost:8096

# 1. Health
curl -sS $BASE/actuator/health | jq .

# 2. Webhook (auth-blocked in dev — security order issue)
curl -sS -X POST -H "Content-Type: application/json" \
  $BASE/api/payments/webhooks/stripe \
  -d '{"id":"evt_smoke","type":"payment_intent.succeeded","data":{"object":{"id":"pi_001","amount":2000,"currency":"vnd","metadata":{"saga_step_id":"step-A"}}}}' \
  -w "\n[HTTP %{http_code}]\n"
# Expected: HTTP 403 (security filter blocks it; in prod signature verification needed)

# 3. Direct DB — verify Story 3.2 dedup table exists
docker exec ecommerce-platform-postgres-1 psql -U postgres -d payment_db -c "\dt"
docker exec ecommerce-platform-postgres-1 psql -U postgres -d payment_db -c "SELECT count(*) FROM webhook_dedup"
docker exec ecommerce-platform-postgres-1 psql -U postgres -d payment_db -c "SELECT event_id, count(*) FROM webhook_dedup GROUP BY event_id ORDER BY 1 DESC LIMIT 5"
```

---

## Health check all 4 in one line

```bash
for port in 8091 8093 8095 8096; do
  printf "  :%s  " "$port"
  curl -sS -m 3 http://localhost:$port/actuator/health | jq -c .status 2>/dev/null || echo "DOWN"
done
```

Expected:
```
  :8091  "UP"
  :8093  "UP"
  :8095  "UP"
  :8096  "UP"
```

---

## Next

- [50-services-overview.md](50-services-overview.md) — what each service does
- [30-known-issues.md](30-known-issues.md) — what doesn't work
- [60-troubleshoot.md](60-troubleshoot.md) — common errors
