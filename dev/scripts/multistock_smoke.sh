#!/usr/bin/env bash
# dev/scripts/multistock_smoke.sh — Story 1.7 / FR-10 end-to-end smoke.
# Verifies multi-warehouse region dispatch:
#   1. Seed HCM-01 with 10 units, HN-01 with 5 units of variant 100.
#   2. GET /api/inventory/variants/100/on-hand — per-warehouse breakdown.
#   3. Reserve 7 units shipping to SOUTH → picker picks HCM-01 (in-region).
#   4. Drain HCM-01 to 0; reserve 5 units shipping to SOUTH → picker falls back to HN-01
#      (cross-region, WARN log).
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

# Resolve HCM-01 / HN-01 uuids (seeded by V005; ON CONFLICT DO NOTHING keeps idempotent).
HCM_ID=$(psql_inv -tAc "SELECT uuid FROM warehouses WHERE code = 'HCM-01'" | tr -d ' ')
HN_ID=$(psql_inv -tAc "SELECT uuid FROM warehouses WHERE code = 'HN-01'" | tr -d ' ')

if [ -z "${HCM_ID}" ] || [ -z "${HN_ID}" ]; then
  printf '✗ warehouses HCM-01 / HN-01 not seeded (V005 not applied); aborting.\n' >&2
  exit 1
fi

# 1. Seed: HCM-01 +10, HN-01 +5.
psql_inv -tAc \
  "INSERT INTO inventory_ledger (uuid, variant_id, warehouse_id, delta, reason, event_id, tenant_id) \
   VALUES (2001, 100, ${HCM_ID}, 10, 'receive', 3001, 'default') ON CONFLICT (uuid) DO NOTHING" \
  >/dev/null
psql_inv -tAc \
  "INSERT INTO inventory_ledger (uuid, variant_id, warehouse_id, delta, reason, event_id, tenant_id) \
   VALUES (2002, 100, ${HN_ID}, 5, 'receive', 3002, 'default') ON CONFLICT (uuid) DO NOTHING" \
  >/dev/null
report 0 "seed: HCM-01 +10, HN-01 +5 of variant 100"

# 2. Read breakdown.
breakdown=$(curl -sf "${INVENTORY_URL}/api/inventory/variants/100/on-hand")
if echo "${breakdown}" | grep -q "\"warehouseId\":${HCM_ID}" && echo "${breakdown}" | grep -q "\"warehouseId\":${HN_ID}"; then
  report 0 "GET /api/inventory/variants/100/on-hand — 2 warehouses in breakdown"
else
  report 1 "GET /api/inventory/variants/100/on-hand — breakdown missing rows: ${breakdown}"
fi

# 3. Reserve 7 from SOUTH → expect HCM-01 (warehouseId=${HCM_ID}).
step1="multistock-step-1-$(date +%s)"
resp1=$(curl -s -X POST "${INVENTORY_URL}/api/inventory-reservations" \
  -H "Content-Type: application/json" \
  -d "{\"variantId\":100,\"shippingRegion\":\"SOUTH\",\"quantity\":7,\"sagaStepId\":\"${step1}\"}")
if echo "${resp1}" | grep -q "\"warehouseId\":${HCM_ID}" && echo "${resp1}" | grep -q "\"status\":\"ACTIVE\""; then
  report 0 "POST south reserve 7 → HCM-01 (in-region pick)"
else
  report 1 "POST south reserve 7 — expected HCM-01, got: ${resp1}"
fi

# 4. Drain HCM-01 to 0 (delta=-10), then reserve 5 from SOUTH → expect HN-01 (cross-region).
psql_inv -tAc \
  "INSERT INTO inventory_ledger (uuid, variant_id, warehouse_id, delta, reason, event_id, tenant_id) \
   VALUES (2003, 100, ${HCM_ID}, -10, 'adjust', 3003, 'default') ON CONFLICT (uuid) DO NOTHING" \
  >/dev/null

step2="multistock-step-2-$(date +%s)"
resp2=$(curl -s -X POST "${INVENTORY_URL}/api/inventory-reservations" \
  -H "Content-Type: application/json" \
  -d "{\"variantId\":100,\"shippingRegion\":\"SOUTH\",\"quantity\":5,\"sagaStepId\":\"${step2}\"}")
if echo "${resp2}" | grep -q "\"warehouseId\":${HN_ID}" && echo "${resp2}" | grep -q "\"status\":\"ACTIVE\""; then
  report 0 "POST south reserve 5 (HCM drained) → HN-01 (cross-region fallback)"
else
  report 1 "POST south reserve 5 — expected HN-01 cross-region, got: ${resp2}"
fi

total=$((pass + fail))
printf '\n%d/%d multi-warehouse smoke checks passed.\n' "$pass" "$total"

if [ "$fail" -gt 0 ]; then
  exit 1
fi
exit 0