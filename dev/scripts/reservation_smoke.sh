#!/usr/bin/env bash
# dev/scripts/reservation_smoke.sh — Story 1.6 / FR-9 end-to-end smoke.
# Verifies the reservation lifecycle:
#   1. seed an inventory_ledger row (delta=+1)
#   2. POST /api/inventory-reservations (expect 201 Created)
#   3. concurrent: 2 parallel POSTs with same variant+warehouse+qty=1; expect 1×201 + 1×409
#   4. TTL expiry: UPDATE expires_at = now() - 1m; wait 30s; expect status=RELEASED
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

# 1. Seed ledger: variant=100, warehouse=1, delta=+1, reason=receive.
psql_inv -tAc \
  "INSERT INTO inventory_ledger (uuid, variant_id, warehouse_id, delta, reason, event_id, tenant_id) \
   VALUES (1, 100, 1, 1, 'receive', 1001, 'default') ON CONFLICT (uuid) DO NOTHING" \
  >/dev/null
report 0 "seed: ledger row variant=100 warehouse=1 delta=+1"

# 2. POST reservation (expect 201).
step="smoke-step-$(date +%s)"
http_code=$(curl -s -o /tmp/reservation_response.json -w "%{http_code}" \
  -X POST "${INVENTORY_URL}/api/inventory-reservations" \
  -H "Content-Type: application/json" \
  -d "{\"variantId\":100,\"warehouseId\":1,\"quantity\":1,\"sagaStepId\":\"${step}\"}")
if [ "${http_code}" = "201" ]; then
  report 0 "POST /api/inventory-reservations -> 201"
else
  report 1 "POST /api/inventory-reservations -> ${http_code}"
  cat /tmp/reservation_response.json
fi

# 3. Concurrent: 2 parallel POSTs with same variant+warehouse+qty=1; expect 1×201 + 1×409.
# Step 2 already consumed the only unit; available is now 0. Both POSTs must hit the
# FR-9 oversell guard and return 409.
concurrent_step="concurrent-step-$(date +%s)"
rm -f /tmp/concurrent_a.json /tmp/concurrent_b.json /tmp/concurrent_a.code /tmp/concurrent_b.code
(curl -s -o /tmp/concurrent_a.json -w "%{http_code}" \
   -X POST "${INVENTORY_URL}/api/inventory-reservations" \
   -H "Content-Type: application/json" \
   -d "{\"variantId\":100,\"warehouseId\":1,\"quantity\":1,\"sagaStepId\":\"${concurrent_step}-A\"}" \
   > /tmp/concurrent_a.code) &
(curl -s -o /tmp/concurrent_b.json -w "%{http_code}" \
   -X POST "${INVENTORY_URL}/api/inventory-reservations" \
   -H "Content-Type: application/json" \
   -d "{\"variantId\":100,\"warehouseId\":1,\"quantity\":1,\"sagaStepId\":\"${concurrent_step}-B\"}" \
   > /tmp/concurrent_b.code) &
wait
code_a=$(cat /tmp/concurrent_a.code)
code_b=$(cat /tmp/concurrent_b.code)
# Step 2 reserved the only unit; available=0. Both concurrent reserves must 409.
if [ "${code_a}" = "409" ] && [ "${code_b}" = "409" ]; then
  report 0 "concurrent: A=409 B=409 (oversell guard, available=0 after step 2)"
else
  report 1 "concurrent: expected both 409, got A=${code_a} B=${code_b}"
fi

# 4. TTL expiry: backdate expires_at on the smoke reservation, wait, check status=RELEASED.
psql_inv -tAc \
  "UPDATE inventory_reservation SET expires_at = now() - interval '1 minute' \
   WHERE saga_step_id = '${step}'" >/dev/null

# Sweeper runs at fixedDelay=30s; sleep 35s to ensure one tick.
echo "Waiting 35s for sweeper tick..."
sleep 35

status=$(psql_inv -tAc \
  "SELECT status FROM inventory_reservation WHERE saga_step_id = '${step}'" | tr -d ' ')

if [ "${status}" = "RELEASED" ]; then
  report 0 "sweeper: reservation status=RELEASED after TTL expiry"
else
  report 1 "sweeper: expected RELEASED, got '${status}'"
fi

total=$((pass + fail))
printf '\n%d/%d reservation smoke checks passed.\n' "$pass" "$total"

if [ "$fail" -gt 0 ]; then
  exit 1
fi
exit 0