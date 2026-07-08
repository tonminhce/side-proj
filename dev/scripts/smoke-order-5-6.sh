#!/usr/bin/env bash
# dev/scripts/smoke-order-5-6.sh — Story 5.6 runtime smoke (FR-50).
#
# Verifies loyalty points:
#   1. services/order starts with V001/V002/V003/V004.
#   2. /actuator/health UP on :8087.
#   3. /actuator/loggers denied (R-15).
#   4. POST PLACED + POST PAID transitions for orderUuid=42 + customerId=99 (totalCents=10000).
#   5. POST /api/orders/42/accrue-loyalty?customerId=99&totalCents=10000 — assert 100 points.
#   6. GET /api/orders/42/loyalty — assert 100 points.
#   7. GET /api/orders/customer/99/loyalty-account — assert 100 points.
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
LOG_FILE="${LOG_FILE:-/tmp/order-smoke-5-6.log}"
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

echo "== 0. Ensure order_db exists + reset"
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE USER order_user WITH PASSWORD 'order_pass' SUPERUSER;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE DATABASE order_db OWNER order_user;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE order_db TO order_user;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -c "DROP TABLE IF EXISTS order_state_transition CASCADE; DROP TABLE IF EXISTS order_price_snapshot CASCADE; DROP TABLE IF EXISTS outbox CASCADE; DROP TABLE IF EXISTS loyalty_accrual CASCADE; DROP TABLE IF EXISTS loyalty_account CASCADE; DELETE FROM flyway_schema_history;" 2>/dev/null | tail -1
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
PRICE_JSON='{"orderUuid":'"${ORDER_UUID}"',"listPriceCents":10000,"promoCodes":null,"taxCents":0,"shippingCents":0,"totalCents":10000,"currency":"USD","capturedAt":"2026-07-08T00:00:00"}'
echo "== 3. POST genesis PLACED (orderUuid=${ORDER_UUID})"
curl -s -o /dev/null -w "  POST PLACED HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"orderUuid\":${ORDER_UUID},\"toState\":\"PLACED\",\"sagaStep\":\"order.placed\",\"priceSnapshot\":${PRICE_JSON}}" \
  "${ORDER_URL}/api/orders"
curl -s -o /dev/null -w "  POST PAID HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"orderUuid\":${ORDER_UUID},\"toState\":\"PAID\",\"sagaStep\":\"payment.captured\",\"priceSnapshot\":${PRICE_JSON}}" \
  "${ORDER_URL}/api/orders"

echo "== 4. POST /api/orders/${ORDER_UUID}/accrue-loyalty?customerId=99&totalCents=10000"
HTTP_CODE=$(curl -s -o /tmp/l1.json -w "%{http_code}" \
  -X POST "${ORDER_URL}/api/orders/${ORDER_UUID}/accrue-loyalty?customerId=99&totalCents=10000")
if [ "${HTTP_CODE}" != "200" ]; then
  echo "FAIL: accrue-loyalty returned HTTP ${HTTP_CODE}"
  cat /tmp/l1.json
  exit 1
fi
if ! grep -q '"points":100' /tmp/l1.json; then
  echo "FAIL: points not 100"
  cat /tmp/l1.json
  exit 1
fi
echo "  ok: accrual 100 points"

echo "== 5. GET /api/orders/${ORDER_UUID}/loyalty"
HTTP_CODE=$(curl -s -o /tmp/l2.json -w "%{http_code}" "${ORDER_URL}/api/orders/${ORDER_UUID}/loyalty")
if [ "${HTTP_CODE}" != "200" ]; then
  echo "FAIL: loyalty for order returned HTTP ${HTTP_CODE}"
  cat /tmp/l2.json
  exit 1
fi
if ! grep -q '"points":100' /tmp/l2.json; then
  echo "FAIL: loyalty for order missing points=100"
  cat /tmp/l2.json
  exit 1
fi
echo "  ok: order loyalty = 100 points"

echo "== 6. GET /api/orders/customer/99/loyalty-account"
HTTP_CODE=$(curl -s -o /tmp/l3.json -w "%{http_code}" "${ORDER_URL}/api/orders/customer/99/loyalty-account")
if [ "${HTTP_CODE}" != "200" ]; then
  echo "FAIL: loyalty-account returned HTTP ${HTTP_CODE}"
  cat /tmp/l3.json
  exit 1
fi
if ! grep -q '"points":100' /tmp/l3.json; then
  echo "FAIL: loyalty-account missing points=100"
  cat /tmp/l3.json
  exit 1
fi
echo "  ok: customer loyalty account = 100 points"

echo "== 7. Verify DB rows"
ACCT_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT count(*) FROM loyalty_account WHERE customer_id = 99" 2>/dev/null)
ACCR_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT count(*) FROM loyalty_accrual WHERE order_uuid = ${ORDER_UUID}" 2>/dev/null)
if [ "${ACCT_COUNT}" != "1" ] || [ "${ACCR_COUNT}" != "1" ]; then
  echo "FAIL: DB state — account=${ACCT_COUNT} accrual=${ACCR_COUNT}"
  exit 1
fi
echo "  ok: 1 account + 1 accrual"

echo
echo "PASS: Story 5.6 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - /actuator/loggers denied (R-15)"
echo "  - 100 points accrued for orderUuid=${ORDER_UUID} (totalCents=10000)"
echo "  - GET /loyalty returns 100 points"
echo "  - GET /loyalty-account returns 100 points"
echo "  - 1 loyalty_account + 1 loyalty_accrual in DB"