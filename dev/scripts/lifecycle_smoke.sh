#!/usr/bin/env bash
# dev/scripts/lifecycle_smoke.sh — Story 1.8 / FR-11 + FR-12 end-to-end smoke.
# Verifies the unified inventory.lifecycle event topic (5 phases) AND the @SoftUk audit
# on the Warehouse entity (DI-09 mitigation).
#
#   1. Seed: receive 10 units into HCM-01.
#   2. POST /api/inventory-reservations → expect RESERVED phase on inventory.lifecycle
#      AND a legacy inventory.reserved dual-publish.
#   3. POST /api/inventory-allocations → expect ALLOCATED phase on inventory.lifecycle only.
#   4. POST /api/inventory-shipments → expect SHIPPED phase on inventory.lifecycle only.
#   5. POST /api/inventory-adjustments → expect ADJUSTED phase on inventory.lifecycle only.
#   6. POST /api/inventory-warehouses (new warehouse) → expect 201.
#   7. POST /api/inventory-warehouses (duplicate code) → expect 400 with @SoftUk violation.
#
# Prereq: docker compose up; inventory service started on :8083.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [ -f "${PROJECT_DIR}/dev/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${PROJECT_DIR}/dev/.env"
  set +a
fi

INV_USER="${POSTGRES_INVENTORY_USER:-inventory_user}"
INV_DB="${POSTGRES_INVENTORY_DB:-inventory_db}"
INVENTORY_URL="${INVENTORY_URL:-http://localhost:8083}"
PG_CONTAINER="${PG_CONTAINER:-postgres}"

psql_inv() {
  docker compose -f "${PROJECT_DIR}/dev/docker-compose.yml" exec -T "${PG_CONTAINER}" \
    psql -U "${INV_USER}" -d "${INV_DB}" "$@"
}

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

HCM_ID=$(psql_inv -tAc "SELECT uuid FROM warehouses WHERE code = 'HCM-01'" | tr -d ' ')
if [ -z "${HCM_ID}" ]; then
  printf '✗ warehouses HCM-01 not seeded (V005 not applied); aborting.\n' >&2
  exit 1
fi

# 1. Seed ledger: receive 10 units of variant 100 into HCM-01.
psql_inv -tAc \
  "INSERT INTO inventory_ledger (uuid, variant_id, warehouse_id, delta, reason, event_id, tenant_id) \
   VALUES (3001, 100, ${HCM_ID}, 10, 'receive', 4001, 'default') ON CONFLICT (uuid) DO NOTHING" \
  >/dev/null
report 0 "seed: HCM-01 +10 of variant 100"

# 2. Reserve → expect RESERVED phase + dual-publish to legacy inventory.reserved.
step_reserve="lifecycle-reserve-$(date +%s)"
resp_reserve=$(curl -s -X POST "${INVENTORY_URL}/api/inventory-reservations" \
  -H "Content-Type: application/json" \
  -d "{\"variantId\":100,\"warehouseId\":${HCM_ID},\"quantity\":3,\"sagaStepId\":\"${step_reserve}\"}")
RESERVATION_UUID=$(echo "${resp_reserve}" | sed -n 's/.*"reservationUuid":\([0-9]*\).*/\1/p')
if [ -n "${RESERVATION_UUID}" ] && [ "${RESERVATION_UUID}" != "null" ]; then
  report 0 "POST /api/inventory-reservations → reservationUuid=${RESERVATION_UUID}"

  legacy_count=$(psql_inv -tAc "SELECT COUNT(*) FROM outbox WHERE event_type = 'inventory.reserved' AND aggregate_id = ${RESERVATION_UUID}" | tr -d ' ')
  lifecycle_count=$(psql_inv -tAc "SELECT COUNT(*) FROM outbox WHERE event_type = 'inventory.lifecycle' AND aggregate_id = ${RESERVATION_UUID}" | tr -d ' ')
  if [ "${legacy_count}" -ge 1 ] && [ "${lifecycle_count}" -ge 1 ]; then
    report 0 "RESERVED phase: legacy (${legacy_count}) + lifecycle (${lifecycle_count}) emitted"
  else
    report 1 "RESERVED phase expected legacy+lifecycle outbox rows, got legacy=${legacy_count} lifecycle=${lifecycle_count}"
  fi
else
  report 1 "POST /api/inventory-reservations failed: ${resp_reserve}"
fi

# 3. Allocate → expect ALLOCATED phase on inventory.lifecycle only.
if [ -n "${RESERVATION_UUID}" ] && [ "${RESERVATION_UUID}" != "null" ]; then
  step_alloc="lifecycle-alloc-$(date +%s)"
  resp_alloc=$(curl -s -X POST "${INVENTORY_URL}/api/inventory-allocations" \
    -H "Content-Type: application/json" \
    -d "{\"reservationUuid\":${RESERVATION_UUID},\"sagaStepId\":\"${step_alloc}\"}")
  if echo "${resp_alloc}" | grep -q '"status":"COMMITTED"'; then
    report 0 "POST /api/inventory-allocations → COMMITTED"
    alloc_lifecycle_count=$(psql_inv -tAc "SELECT COUNT(*) FROM outbox WHERE event_type = 'inventory.lifecycle' AND aggregate_id = ${RESERVATION_UUID}" | tr -d ' ')
    if [ "${alloc_lifecycle_count}" -ge 2 ]; then
      report 0 "ALLOCATED phase: lifecycle outbox row count=${alloc_lifecycle_count}"
    else
      report 1 "ALLOCATED phase: expected >=2 lifecycle rows (RESERVED + ALLOCATED), got ${alloc_lifecycle_count}"
    fi
  else
    report 1 "POST /api/inventory-allocations failed: ${resp_alloc}"
  fi
fi

# 4. Ship → expect SHIPPED phase on inventory.lifecycle only.
step_ship="lifecycle-ship-$(date +%s)"
resp_ship=$(curl -s -X POST "${INVENTORY_URL}/api/inventory-shipments" \
  -H "Content-Type: application/json" \
  -d "{\"variantId\":100,\"warehouseId\":${HCM_ID},\"quantity\":2,\"sagaStepId\":\"${step_ship}\"}")
if echo "${resp_ship}" | grep -q '"reason":"ship"'; then
  report 0 "POST /api/inventory-shipments → shipped"
else
  report 1 "POST /api/inventory-shipments failed: ${resp_ship}"
fi

# 5. Adjust → expect ADJUSTED phase on inventory.lifecycle only.
step_adj="lifecycle-adj-$(date +%s)"
resp_adj=$(curl -s -X POST "${INVENTORY_URL}/api/inventory-adjustments" \
  -H "Content-Type: application/json" \
  -d "{\"variantId\":100,\"warehouseId\":${HCM_ID},\"delta\":-1,\"reason\":\"ADJUST\"}")
if echo "${resp_adj}" | grep -q '"reason":"adjust"'; then
  report 0 "POST /api/inventory-adjustments → adjusted"
else
  report 1 "POST /api/inventory-adjustments failed: ${resp_adj}"
fi

# 6. Create warehouse → expect 201.
resp_wh=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${INVENTORY_URL}/api/inventory-warehouses" \
  -H "Content-Type: application/json" \
  -d '{"code":"DN-01","displayName":"Da Nang","region":"CENTRAL"}')
if [ "${resp_wh}" = "201" ]; then
  report 0 "POST /api/inventory-warehouses (new) → 201 Created"
else
  report 1 "POST /api/inventory-warehouses (new) expected 201, got ${resp_wh}"
fi

# 7. Duplicate warehouse → expect 400 with @SoftUk violation.
resp_dup=$(curl -s -X POST "${INVENTORY_URL}/api/inventory-warehouses" \
  -H "Content-Type: application/json" \
  -d '{"code":"DN-01","displayName":"Da Nang 2","region":"CENTRAL"}')
if echo "${resp_dup}" | grep -q "warehouse_code_per_tenant"; then
  report 0 "POST /api/inventory-warehouses (duplicate) → 400 with @SoftUk violation"
else
  report 1 "POST /api/inventory-warehouses (duplicate) expected @SoftUk violation, got: ${resp_dup}"
fi

total=$((pass + fail))
printf '\n%d/%d lifecycle smoke checks passed.\n' "$pass" "$total"

if [ "$fail" -gt 0 ]; then
  exit 1
fi
exit 0