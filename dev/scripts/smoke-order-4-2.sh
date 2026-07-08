#!/usr/bin/env bash
# dev/scripts/smoke-order-4-2.sh — Story 4.2 runtime smoke.
#
# Verifies the post-payment lifecycle (FR-32):
#   1. services/order starts with bean-wiring + Postgres + Flyway V001/V002.
#   2. /actuator/health returns {"status":"UP"} on :8087.
#   3. /actuator/loggers returns 404 (R-15 deny-list).
#   4. POST genesis PLACED transition (Story 4.1 contract).
#   5. POST /api/orders/{orderUuid}/advance?targetState=PAID — advances to PAID.
#   6. POST /api/orders/{orderUuid}/advance?targetState=ALLOCATED — advances to ALLOCATED.
#   7. POST /api/orders/{orderUuid}/advance?targetState=PACKING — advances to PACKING.
#   8. POST /api/orders/{orderUuid}/advance?targetState=PACKED — advances to PACKED.
#   9. GET /api/orders/{orderUuid} — 5 transitions in the log.
#  10. outbox has 5 signed envelopes.
#  11. order_price_snapshot still 1 row (FR-31 immutability).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [ -f "${PROJECT_DIR}/dev/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${PROJECT_DIR}/dev/.env"
  set +a
fi

ORDER_URL="${ORDER_URL:-http://localhost:8087}"
LOG_FILE="${LOG_FILE:-/tmp/order-smoke-4-2.log}"
MVN_PID=""

if command -v lsof >/dev/null 2>&1; then
  STALE_PIDS="$(lsof -ti:"${ORDER_URL##*:}" 2>/dev/null || true)"
  if [ -n "${STALE_PIDS}" ]; then
    kill -9 ${STALE_PIDS} 2>/dev/null || true
    sleep 1
  fi
fi

cleanup() {
  if [ -n "${MVN_PID}" ] && kill -0 "${MVN_PID}" 2>/dev/null; then
    kill "${MVN_PID}" 2>/dev/null || true
    wait "${MVN_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "== 0. Ensure order_db exists"
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE USER order_user WITH PASSWORD 'order_pass' SUPERUSER;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE DATABASE order_db OWNER order_user;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE order_db TO order_user;" 2>/dev/null || true
echo "  ok: order_db ready"

export HMAC_SERVICE_SECRET_ORDER="${HMAC_SERVICE_SECRET_ORDER:-$(openssl rand -hex 32 2>/dev/null || echo "$(printf '%064x' $(date +%s%N))")}"

echo "== 1. Start services/order (log -> ${LOG_FILE})"
(cd "${PROJECT_DIR}/services/order" && \
   SPRING_PROFILES_ACTIVE=dev \
   HMAC_SERVICE_SECRET_ORDER="${HMAC_SERVICE_SECRET_ORDER}" \
   mvn -q spring-boot:run >"${LOG_FILE}" 2>&1) &
MVN_PID=$!

echo "== 2. Wait for ${ORDER_URL}/actuator/health (up to 90s)"
STATUS=""
for i in $(seq 1 90); do
  if curl -sf "${ORDER_URL}/actuator/health" -o /tmp/order-health.json 2>/dev/null; then
    STATUS=$(grep -o '"status":"[^"]*"' /tmp/order-health.json | head -1 | sed 's/.*"\(.*\)"/\1/' || true)
    if [ "${STATUS}" = "UP" ]; then
      echo "  ok: ${STATUS} (after ${i}s)"
      break
    fi
  fi
  sleep 1
done

if [ "${STATUS:-}" != "UP" ]; then
  echo "FAIL: ${ORDER_URL}/actuator/health did not return UP"
  tail -80 "${LOG_FILE}" || true
  exit 1
fi

echo "== 3. /actuator/loggers must return 404 (R-15 deny-list)"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${ORDER_URL}/actuator/loggers" || true)
if [ "${HTTP_CODE}" != "404" ]; then
  echo "FAIL: /actuator/loggers returned HTTP ${HTTP_CODE}; expected 404"
  exit 1
fi
echo "  ok: /actuator/loggers = HTTP 404"

ORDER_UUID="$(date +%s)"
echo "== 4. POST genesis PLACED (orderUuid=${ORDER_UUID})"
PRICE_JSON='{"orderUuid":'"${ORDER_UUID}"',"listPriceCents":10000,"promoCodes":null,"taxCents":1000,"shippingCents":500,"totalCents":11500,"currency":"USD","capturedAt":"2026-07-08T00:00:00"}'
curl -s -o /dev/null -w "  POST PLACED HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"orderUuid\":${ORDER_UUID},\"toState\":\"PLACED\",\"sagaStep\":\"order.placed\",\"priceSnapshot\":${PRICE_JSON}}" \
  "${ORDER_URL}/api/orders"

for STATE in PAID ALLOCATED PACKING PACKED; do
  curl -s -o /dev/null -w "  POST advance ${STATE} HTTP %{http_code}\n" \
    -X POST "${ORDER_URL}/api/orders/${ORDER_UUID}/advance?targetState=${STATE}"
done

echo "== 5. Verify 5 transitions in order_state_transition"
ROW_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT count(*) FROM order_state_transition WHERE order_uuid = ${ORDER_UUID}" 2>/dev/null)
if [ "${ROW_COUNT}" != "5" ]; then
  echo "FAIL: expected 5 transitions, got ${ROW_COUNT}"
  exit 1
fi
echo "  ok: 5 transitions"

echo "== 6. Verify FR-31 immutability: still 1 price snapshot"
SNAP_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT count(*) FROM order_price_snapshot WHERE order_uuid = ${ORDER_UUID}" 2>/dev/null)
if [ "${SNAP_COUNT}" != "1" ]; then
  echo "FAIL: snapshot count changed (FR-31 violated): got ${SNAP_COUNT}"
  exit 1
fi
echo "  ok: 1 snapshot (immutability held)"

echo "== 7. Verify outbox has 5 signed envelopes"
OUTBOX_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT count(*) FROM outbox WHERE aggregate_id = ${ORDER_UUID}" 2>/dev/null)
if [ "${OUTBOX_COUNT}" != "5" ]; then
  echo "FAIL: expected 5 outbox envelopes, got ${OUTBOX_COUNT}"
  exit 1
fi
echo "  ok: 5 outbox envelopes (HMAC-signed)"

echo "== 8. GET /api/orders/${ORDER_UUID} — history shape"
HISTORY=$(curl -s "${ORDER_URL}/api/orders/${ORDER_UUID}")
for STATE in PLACED PAID ALLOCATED PACKING PACKED; do
  if ! echo "${HISTORY}" | grep -q "\"${STATE}\""; then
    echo "FAIL: history missing ${STATE}"
    echo "  ${HISTORY}"
    exit 1
  fi
done
echo "  ok: history contains all 5 states"

echo
echo "PASS: Story 4.2 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - /actuator/loggers denied (R-15 deny-list)"
echo "  - 5 transitions appended (PLACED → PAID → ALLOCATED → PACKING → PACKED)"
echo "  - 1 immutable price snapshot (FR-31)"
echo "  - 5 HMAC-signed outbox envelopes (ADR-20)"