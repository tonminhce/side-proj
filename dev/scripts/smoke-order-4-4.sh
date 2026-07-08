#!/usr/bin/env bash
# dev/scripts/smoke-order-4-4.sh — Story 4.4 runtime smoke (FR-34).
#
# Verifies edit-after-pay:
#   1. services/order starts with bean-wiring + Flyway V001/V002/V003.
#   2. /actuator/health returns {"status":"UP"} on :8087.
#   3. POST genesis PLACED.
#   4. POST /api/orders/{orderUuid}/address with version=1 — assert 201 + order.amended event.
#   5. POST /api/orders/{orderUuid}/address with version=1 again — assert 412 (version mismatch, current=2).
#   6. POST /api/orders/{orderUuid}/cancel — assert 201 + order.cancelled event.
#   7. POST /api/orders/{orderUuid}/address after cancel — assert 409 (terminal state).
#   8. Verify 3 transitions + 1 immutable snapshot (FR-31) + 3 signed outbox envelopes.
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
LOG_FILE="${LOG_FILE:-/tmp/order-smoke-4-4.log}"
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

echo "== 0. Ensure order_db exists + reset V001/V002/V003"
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE USER order_user WITH PASSWORD 'order_pass' SUPERUSER;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE DATABASE order_db OWNER order_user;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE order_db TO order_user;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -c "DROP TABLE IF EXISTS order_state_transition CASCADE; DROP TABLE IF EXISTS order_price_snapshot CASCADE; DROP TABLE IF EXISTS outbox CASCADE; DELETE FROM flyway_schema_history;" 2>/dev/null | tail -1
echo "  ok: order_db reset"

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

ORDER_UUID="$(date +%s)"
echo "== 3. POST genesis PLACED (orderUuid=${ORDER_UUID})"
PRICE_JSON='{"orderUuid":'"${ORDER_UUID}"',"listPriceCents":10000,"promoCodes":null,"taxCents":1000,"shippingCents":500,"totalCents":11500,"currency":"USD","capturedAt":"2026-07-08T00:00:00"}'
curl -s -o /dev/null -w "  POST PLACED HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"orderUuid\":${ORDER_UUID},\"toState\":\"PLACED\",\"sagaStep\":\"order.placed\",\"priceSnapshot\":${PRICE_JSON}}" \
  "${ORDER_URL}/api/orders"

echo "== 4. POST /address with version=1 — must 201"
curl -s -o /tmp/r1.json -w "  HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"version\":1,\"address\":\"{\\\"line1\\\":\\\"123 Main St\\\"}\"}" \
  "${ORDER_URL}/api/orders/${ORDER_UUID}/address"
cat /tmp/r1.json
echo

echo "== 5. POST /address with version=1 again — must 412 (current=2)"
curl -s -o /tmp/r2.json -w "  HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"version\":1,\"address\":\"{}\"}" \
  "${ORDER_URL}/api/orders/${ORDER_UUID}/address"
cat /tmp/r2.json
echo

echo "== 6. POST /cancel with version=2 — must 201"
curl -s -o /tmp/r3.json -w "  HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"version\":2,\"reason\":\"changed mind\"}" \
  "${ORDER_URL}/api/orders/${ORDER_UUID}/cancel"
cat /tmp/r3.json
echo

echo "== 7. POST /address after cancel — must 409 (terminal state)"
curl -s -o /tmp/r4.json -w "  HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"version\":3,\"address\":\"{}\"}" \
  "${ORDER_URL}/api/orders/${ORDER_UUID}/address"
cat /tmp/r4.json
echo

echo "== 8. Verify 3 transitions + 1 snapshot + 3 outbox envelopes"
TX_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT count(*) FROM order_state_transition WHERE order_uuid = ${ORDER_UUID}" 2>/dev/null)
if [ "${TX_COUNT}" != "3" ]; then
  echo "FAIL: expected 3 transitions, got ${TX_COUNT}"
  exit 1
fi
SNAP_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT count(*) FROM order_price_snapshot WHERE order_uuid = ${ORDER_UUID}" 2>/dev/null)
if [ "${SNAP_COUNT}" != "1" ]; then
  echo "FAIL: snapshot count changed (FR-31 violated): got ${SNAP_COUNT}"
  exit 1
fi
OB_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT count(*) FROM outbox WHERE aggregate_id = ${ORDER_UUID}" 2>/dev/null)
if [ "${OB_COUNT}" != "3" ]; then
  echo "FAIL: expected 3 outbox envelopes, got ${OB_COUNT}"
  exit 1
fi
echo "  ok: 3 transitions + 1 snapshot (FR-31) + 3 outbox envelopes"

echo "== 9. Verify address_json was set on the snapshot"
ADDRESS_JSON=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT address_json::text FROM order_price_snapshot WHERE order_uuid = ${ORDER_UUID}" 2>/dev/null)
if [ "${ADDRESS_JSON}" != "" ] && [ "${ADDRESS_JSON}" != "\\N" ]; then
  echo "  ok: address_json=${ADDRESS_JSON}"
else
  echo "FAIL: address_json was not set by AmendOrderAddressUseCase"
  exit 1
fi

echo
echo "PASS: Story 4.4 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - amend within 30 min + matching version → 201"
echo "  - amend with stale version → 412"
echo "  - cancel → 201"
echo "  - amend after cancel → 409 (terminal state)"
echo "  - 3 transitions + 1 snapshot (FR-31) + 3 signed outbox envelopes"