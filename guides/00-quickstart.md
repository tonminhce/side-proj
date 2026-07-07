# 00 — Quickstart (5 minutes)

> TL;DR: bring up infra → build → start 4 working services → curl health.

## Prereqs (already installed on your Mac)

- Docker Desktop (or `colima` + `docker-cli`)
- Maven 3.9+
- JDK 25 (project uses `--release 25`)
- `jq`, `psql`, `curl`

## 1. Start infra (postgres + 6 other services)

```bash
cd /Users/tonminh-mac/IdeaProjects/side-proj/dev
docker compose -f docker-compose.yml up -d
docker compose -f docker-compose.yml ps
```

You should see 7 containers: `apicurio`, `elasticsearch`, `kafka`, `minio`, `opa`, `postgres`, `redis` — all `Up (healthy)`.

Per-service databases are created on first boot (see `dev/postgres-init/0{1..5}-*.sql`):

- `catalog_db` (catalog_user)
- `inventory_db` (inventory_user)
- `cart_db` (cart_user)
- `checkout_db` (checkout_user)
- `payment_db` (payment_user)

## 2. Build util + 5 services that have code

```bash
cd /Users/tonminh-mac/IdeaProjects/side-proj
mvn -pl util install -q -o -DskipTests
mvn -pl services/catalog,services/inventory,services/cart,services/checkout,services/payment \
  -am package -q -o -DskipTests
```

`util` is a library — install it locally first. Services depend on it.

## 3. Start a service

```bash
# Catalog (port 8091)
SPRING_PROFILES_ACTIVE=dev \
POSTGRES_HOST=localhost POSTGRES_PORT=5432 \
POSTGRES_CATALOG_USER=catalog_user POSTGRES_CATALOG_PASSWORD=catalog_pass POSTGRES_CATALOG_DB=catalog_db \
mvn -f services/catalog/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=8091

# In another terminal: Inventory (port 8093)
SPRING_PROFILES_ACTIVE=dev \
POSTGRES_HOST=localhost POSTGRES_PORT=5432 \
POSTGRES_INVENTORY_USER=inventory_user POSTGRES_INVENTORY_PASSWORD=inventory_pass POSTGRES_INVENTORY_DB=inventory_db \
CATALOG_EVENTS_HMAC_SECRET=dev-only-secret-do-not-use-in-prod \
mvn -f services/inventory/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=8093

# Cart (port 8095)
SPRING_PROFILES_ACTIVE=dev \
POSTGRES_HOST=localhost POSTGRES_PORT=5432 \
POSTGRES_CART_USER=cart_user POSTGRES_CART_PASSWORD=cart_pass POSTGRES_CART_DB=cart_db \
mvn -f services/cart/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=8095

# Payment (port 8096)
SPRING_PROFILES_ACTIVE=dev \
POSTGRES_HOST=localhost POSTGRES_PORT=5432 \
POSTGRES_PAYMENT_USER=payment_user POSTGRES_PAYMENT_PASSWORD=payment_pass POSTGRES_PAYMENT_DB=payment_db \
mvn -f services/payment/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=8096
```

## 4. Verify

```bash
# All four should return {"status":"UP","db":{"status":"UP"}}
curl -sS http://localhost:8091/actuator/health | jq .   # catalog
curl -sS http://localhost:8093/actuator/health | jq .   # inventory
curl -sS http://localhost:8095/actuator/health | jq .   # cart
curl -sS http://localhost:8096/actuator/health | jq .   # payment
```

## 5. Inspect the DBs

```bash
docker exec ecommerce-platform-postgres-1 psql -U postgres -d catalog_db -c "\dt"
docker exec ecommerce-platform-postgres-1 psql -U postgres -d payment_db -c "SELECT * FROM webhook_dedup LIMIT 5"
docker exec ecommerce-platform-postgres-1 psql -U postgres -d inventory_db -c "SELECT * FROM inventory_ledger_entry LIMIT 5"
```

## 6. Stop everything

```bash
pkill -f "spring-boot:run"      # local only
docker compose -f dev/docker-compose.yml stop   # keep data, stop containers
docker compose -f dev/docker-compose.yml down -v # full wipe (also drops data)
```

## Next steps

- See [20-runtime-smoke.md](20-runtime-smoke.md) for curl tests of all 4 services.
- See [30-known-issues.md](30-known-issues.md) for why **checkout** won't start.
- See [50-services-overview.md](50-services-overview.md) for what each service does.
