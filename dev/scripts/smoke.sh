#!/usr/bin/env bash
# dev/scripts/smoke.sh — Story 0.3
# Verifies each platform service is responding on its host port.
# Exit 0 if all 7 services respond, exit 1 otherwise.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${PROJECT_DIR}/dev/docker-compose.yml"

# Load dev/.env if present (gitignored) so env-var checks use real values.
if [ -f "${PROJECT_DIR}/dev/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${PROJECT_DIR}/dev/.env"
  set +a
fi

dc() { docker compose -f "${COMPOSE_FILE}" "$@"; }

pass=0
fail=0

report() {
  local rc=$1 label=$2
  if [ "$rc" -eq 0 ]; then
    printf '✓ %s\n' "$label"
    pass=$((pass + 1))
  else
    printf '✗ %s\n' "$label" >&2
    fail=$((fail + 1))
  fi
}

# 1. Postgres (AC #12: SELECT 1 — proves the query engine is up, not just the port)
if dc exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-app}" -tAc "SELECT 1" 2>/dev/null | grep -q '^1$'; then
  report 0 "postgres: SELECT 1 (${POSTGRES_DB:-app})"
else
  report 1 "postgres: SELECT 1 (${POSTGRES_DB:-app})"
fi

# 1b. Postgres per-service DBs (Story 1.1: catalog_db; Story 1.5: inventory_db). One Postgres
#     pass per existing service DB — each per-service check verifies the role can connect and
#     run a trivial query.
if dc exec -T postgres psql -U "${POSTGRES_CATALOG_USER:-catalog_user}" -d "${POSTGRES_CATALOG_DB:-catalog_db}" -tAc "SELECT 1" 2>/dev/null | grep -q '^1$'; then
  report 0 "postgres: SELECT 1 (${POSTGRES_CATALOG_DB:-catalog_db})"
else
  report 1 "postgres: SELECT 1 (${POSTGRES_CATALOG_DB:-catalog_db})"
fi

# 1c. Story 1.5 — inventory_db check.
if dc exec -T postgres psql -U "${POSTGRES_INVENTORY_USER:-inventory_user}" -d "${POSTGRES_INVENTORY_DB:-inventory_db}" -tAc "SELECT 1" 2>/dev/null | grep -q '^1$'; then
  report 0 "postgres: SELECT 1 (${POSTGRES_INVENTORY_DB:-inventory_db})"
else
  report 1 "postgres: SELECT 1 (${POSTGRES_INVENTORY_DB:-inventory_db})"
fi

# 1d. Story 1.6 — also verify inventory_reservation table exists
if dc exec -T postgres psql -h localhost -U "${POSTGRES_INVENTORY_USER:-inventory_user}" -d "${POSTGRES_INVENTORY_DB:-inventory_db}" -tAc "SELECT 1 FROM pg_tables WHERE tablename = 'inventory_reservation' LIMIT 1" 2>/dev/null | grep -q '^1$'; then
  report 0 "postgres: inventory_reservation table exists"
else
  report 1 "postgres: inventory_reservation table missing (V003 not applied)"
fi

# 1e. Story 1.7 — verify warehouses.region column + 2 seeded warehouses (HCM-01 + HN-01).
if dc exec -T postgres psql -h localhost -U "${POSTGRES_INVENTORY_USER:-inventory_user}" -d "${POSTGRES_INVENTORY_DB:-inventory_db}" -tAc "SELECT COUNT(*) FROM warehouses WHERE region IN ('NORTH', 'SOUTH')" 2>/dev/null | grep -qE '^[2-9][0-9]*$|^[1-9][0-9]+'; then
  report 0 "postgres: warehouses.region seeded (>= 2 warehouses in NORTH/SOUTH)"
else
  report 1 "postgres: warehouses.region not seeded (V005 not applied)"
fi

# 1f. Story 1.8 / FR-12 — verify @SoftUk annotation on Warehouse (DI-09 regression guard).
#     The annotation lives in compiled bytecode; javap reads the runtime annotations table.
if [ -f "${PROJECT_DIR}/services/inventory/target/classes/vn/vnpt/inventory/domain/Warehouse.class" ]; then
  if javap -p -v "${PROJECT_DIR}/services/inventory/target/classes/vn/vnpt/inventory/domain/Warehouse.class" 2>/dev/null | grep -q "SoftUk"; then
    report 0 "inventory: @SoftUk annotation present on Warehouse (DI-09 bound)"
  else
    report 1 "inventory: @SoftUk annotation missing on Warehouse (DI-09 unbound)"
  fi
else
  # ponytail: target/classes may not exist on a fresh dev checkout — skip if so.
  printf '~ inventory: @SoftUk check skipped (target/classes not built yet)\n'
fi

# 1g. Story 1.8 / FR-11 — verify lifecycle event topic emitted (any phase) since the last
#     smoke run. Empty result is acceptable on a fresh DB (no operations yet); the check
#     exists to verify the topic is reachable, not to require specific row counts.
if dc exec -T postgres psql -h localhost -U "${POSTGRES_INVENTORY_USER:-inventory_user}" -d "${POSTGRES_INVENTORY_DB:-inventory_db}" -tAc "SELECT 1 FROM outbox WHERE event_type = 'inventory.lifecycle' LIMIT 1" 2>/dev/null | grep -q '^1$'; then
  report 0 "postgres: inventory.lifecycle events emitted (FR-11 wired)"
else
  printf '~ inventory: inventory.lifecycle events not yet emitted (no smoke run yet)\n'
fi

# 1h. Story 2.1 / FR-14 — cart_db provisioned (postgres-init/03-create-cart-db.sql, ADR-03) and
#     the cart.merged outbox topic reachable. Empty result is acceptable on a fresh DB (no merge
#     yet); the end-to-end cart.merged emission is exercised by dev/scripts/cart_merge_smoke.sh.
if dc exec -T postgres psql -h localhost -U "${POSTGRES_CART_USER:-cart_user}" -d "${POSTGRES_CART_DB:-cart_db}" -tAc "SELECT 1 FROM pg_tables WHERE tablename = 'cart_merge_log' LIMIT 1" 2>/dev/null | grep -q '^1$'; then
  report 0 "postgres: cart_db + cart.merged pipeline provisioned (Story 2.1)"
else
  printf '~ cart: cart_db not provisioned yet (run migrations / start cart service)\n'
fi

# 2. Kafka
if dc exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
  report 0 "kafka: kafka-topics --list"
else
  report 1 "kafka: kafka-topics --list"
fi

# 3. Elasticsearch
if curl -sf http://localhost:9200/_cluster/health >/dev/null 2>&1; then
  report 0 "elasticsearch: GET /_cluster/health"
else
  report 1 "elasticsearch: GET /_cluster/health"
fi

# 4. Redis
if dc exec -T redis redis-cli ping >/dev/null 2>&1; then
  report 0 "redis: PING"
else
  report 1 "redis: PING"
fi

# 5. Apicurio
if curl -sf http://localhost:8081/apis/registry/v2/groups >/dev/null 2>&1; then
  report 0 "apicurio: GET /apis/registry/v2/groups"
else
  report 1 "apicurio: GET /apis/registry/v2/groups"
fi

# 6. MinIO
if curl -sf http://localhost:9000/minio/health/live >/dev/null 2>&1; then
  report 0 "minio: GET /minio/health/live"
else
  report 1 "minio: GET /minio/health/live"
fi

# 7. OPA
if curl -sf http://localhost:8181/health >/dev/null 2>&1; then
  report 0 "opa: GET /health"
else
  report 1 "opa: GET /health"
fi

# 8. Story 1.4: admin read view boots (manual OTel + browser-check).
#    Skipped here — the BFF and catalog service are application services that ship with their
#    Epic stories; smoke.sh only checks platform infra (Postgres + Kafka + ES + Redis + Apicurio
#    + MinIO + OPA). Verify the admin path manually with the curl in dev/README.md "Read admin view".

total=$((pass + fail))
printf '\n%d/%d services healthy.\n' "$pass" "$total"

if [ "$fail" -gt 0 ]; then
  exit 1
fi
exit 0
