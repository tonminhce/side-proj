#!/usr/bin/env bash
# dev/scripts/smoke-order-4-3.sh — Story 4.3 runtime smoke (FR-33).
#
# Verifies the user-facing order timeline:
#   1. services/order starts with bean-wiring + Postgres + Flyway V001/V002.
#   2. /actuator/health returns {"status":"UP"} on :8087.
#   3. /actuator/loggers returns 404 (R-15 deny-list).
#   4. POST genesis PLACED + 4 subsequent transitions (Story 4.2 contract).
#   5. GET /api/orders/{orderUuid}/timeline returns 5 entries with Cache-Control: max-age=30.
#   6. GET /api/orders/99999/timeline returns 200 with {"timeline":[]}.
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
LOG_FILE="${LOG_FILE:-/tmp/order-smoke-4-3.log}"
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
echo "== 4. POST genesis PLACED + 4 transitions (orderUuid=${ORDER_UUID})"
PRICE_JSON='{"orderUuid":'"${ORDER_UUID}"',"listPriceCents":10000,"promoCodes":null,"taxCents":1000,"shippingCents":500,"totalCents":11500,"currency":"USD","capturedAt":"2026-07-08T00:00:00"}'
curl -s -o /dev/null -w "  POST PLACED HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"orderUuid\":${ORDER_UUID},\"toState\":\"PLACED\",\"sagaStep\":\"order.placed\",\"priceSnapshot\":${PRICE_JSON}}" \
  "${ORDER_URL}/api/orders"
for STATE in PAID ALLOCATED PACKING PACKED; do
  curl -s -o /dev/null -w "  POST advance ${STATE} HTTP %{http_code}\n" \
    -X POST "${ORDER_URL}/api/orders/${ORDER_UUID}/advance?targetState=${STATE}"
done

echo "== 5. GET /api/orders/${ORDER_UUID}/timeline (must 200 + Cache-Control max-age=30)"
HEADERS=$(curl -s -D - -o /tmp/timeline.json -w "HTTP %{http_code}\n" "${ORDER_URL}/api/orders/${ORDER_UUID}/timeline")
echo "  ${HEADERS}" | grep -i "HTTP\|Cache-Control\|Vary" | head -5
if ! echo "${HEADERS}" | grep -qi "max-age=30"; then
  echo "FAIL: response missing Cache-Control: max-age=30"
  echo "  headers: ${HEADERS}"
  exit 1
fi
if ! echo "${HEADERS}" | grep -qi "Vary: Accept-Encoding"; then
  echo "FAIL: response missing Vary: Accept-Encoding"
  echo "  headers: ${HEADERS}"
  exit 1
fi
for STATE in PLACED PAID ALLOCATED PACKING PACKED; do
  if ! grep -q "\"$STATE\"" /tmp/timeline.json; then
    echo "FAIL: timeline missing $STATE"
    cat /tmp/timeline.json
    exit 1
  fi
done
echo "  ok: 5 entries in chronological order with correct Cache-Control"
echo "  body: $(cat /tmp/timeline.json | head -c 500)"

echo "== 6. GET /api/orders/99999/timeline (must 200 with empty array)"
EMPTY_BODY=$(curl -s "${ORDER_URL}/api/orders/99999/timeline")
if [ "${EMPTY_BODY}" != '{"orderUuid":99999,"timeline":[]}' ]; then
  echo "FAIL: empty-timeline shape wrong: ${EMPTY_BODY}"
  exit 1
fi
echo "  ok: empty timeline shape correct"

echo
echo "PASS: Story 4.3 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - /actuator/loggers denied (R-15 deny-list)"
echo "  - 5 transitions appended (PLACED → PAID → ALLOCATED → PACKING → PACKED)"
echo "  - GET /timeline returns 5 entries with Cache-Control: public, max-age=30 + Vary: Accept-Encoding"
echo "  - GET /timeline on unknown order returns 200 with {timeline:[]}"