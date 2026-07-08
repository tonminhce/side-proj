#!/usr/bin/env bash
# dev/scripts/smoke-order-4-1.sh — Story 4.1 runtime smoke (AC #8).
#
# Verifies the append-only event log + immutable price snapshot (FR-30, FR-31):
#   1. services/order starts with bean-wiring + Postgres + Flyway V001.
#   2. /actuator/health returns {"status":"UP"} on :8087.
#   3. /actuator/loggers returns 404 (R-15 deny-list).
#   4. POST /api/orders creates the genesis PLACED transition + inserts order_price_snapshot.
#   5. POST /api/orders appends a 2nd transition (PAID) — appends a 2nd row, no snapshot update.
#   6. GET /api/orders/{orderUuid} returns the full history.
#   7. The outbox table has a signed envelope (HMAC contract from Story 3.5).
#
# Prereq: docker compose up (postgres on :5432 with order_db; order_user/order_pass).
# Kills the mvn process on exit.
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
LOG_FILE="${LOG_FILE:-/tmp/order-smoke-4-1.log}"
MVN_PID=""

# Free port 8087 if a previous smoke left a listener.
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

# Ensure order_db exists BEFORE starting the service (the service's Flyway migration fails fast
# if the database doesn't exist). The docker exec hits the running postgres container.
echo "== 0. Ensure order_db exists"
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE USER order_user WITH PASSWORD 'order_pass' SUPERUSER;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE DATABASE order_db OWNER order_user;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE order_db TO order_user;" 2>/dev/null || true
echo "  ok: order_db ready"

export HMAC_SERVICE_SECRET_ORDER="${HMAC_SERVICE_SECRET_ORDER:-$(openssl rand -hex 32 2>/dev/null || echo "$(printf '%064x' $(date +%s%N))")}"
echo "  using HMAC_SERVICE_SECRET_ORDER (length=${#HMAC_SERVICE_SECRET_ORDER})"

echo "== 1. Start services/order (log -> ${LOG_FILE})"
(cd "${PROJECT_DIR}/services/order" && \
   SPRING_PROFILES_ACTIVE=dev \
   HMAC_SERVICE_SECRET_ORDER="${HMAC_SERVICE_SECRET_ORDER}" \
   mvn -q spring-boot:run >"${LOG_FILE}" 2>&1) &
MVN_PID=$!
echo "  mvn pid=${MVN_PID}"

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
  echo "FAIL: ${ORDER_URL}/actuator/health did not return UP within 90s"
  echo "--- tail of ${LOG_FILE} ---"
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
echo "== 4. POST genesis PLACED transition (orderUuid=${ORDER_UUID})"
PRICE_JSON='{"orderUuid":'"${ORDER_UUID}"',"listPriceCents":10000,"promoCodes":null,"taxCents":1000,"shippingCents":500,"totalCents":11500,"currency":"USD","capturedAt":"2026-07-08T00:00:00"}'
RESP=$(curl -s -o /tmp/order-resp.json -w "HTTP %{http_code}" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"orderUuid\":${ORDER_UUID},\"toState\":\"PLACED\",\"sagaStep\":\"order.placed\",\"priceSnapshot\":${PRICE_JSON}}" \
  "${ORDER_URL}/api/orders" 2>&1) || true
echo "  ${RESP}"
if ! echo "${RESP}" | grep -q "HTTP 201"; then
  echo "FAIL: genesis transition did not return 201"
  cat /tmp/order-resp.json
  exit 1
fi

echo "== 5. Verify order_state_transition + order_price_snapshot have 1 row each"
ROW_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT count(*) FROM order_state_transition WHERE order_uuid = ${ORDER_UUID}" 2>/dev/null)
if [ "${ROW_COUNT}" != "1" ]; then
  echo "FAIL: expected 1 row in order_state_transition, got ${ROW_COUNT}"
  exit 1
fi
SNAP_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT count(*) FROM order_price_snapshot WHERE order_uuid = ${ORDER_UUID}" 2>/dev/null)
if [ "${SNAP_COUNT}" != "1" ]; then
  echo "FAIL: expected 1 row in order_price_snapshot, got ${SNAP_COUNT}"
  exit 1
fi
echo "  ok: 1 transition + 1 snapshot"

echo "== 6. POST 2nd transition (PAID) for same orderUuid"
curl -s -o /tmp/order-resp2.json -w "HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"orderUuid\":${ORDER_UUID},\"toState\":\"PAID\",\"sagaStep\":\"payment.captured\",\"priceSnapshot\":${PRICE_JSON}}" \
  "${ORDER_URL}/api/orders"

ROW_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT count(*) FROM order_state_transition WHERE order_uuid = ${ORDER_UUID}" 2>/dev/null)
if [ "${ROW_COUNT}" != "2" ]; then
  echo "FAIL: expected 2 rows in order_state_transition, got ${ROW_COUNT}"
  exit 1
fi
SNAP_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT count(*) FROM order_price_snapshot WHERE order_uuid = ${ORDER_UUID}" 2>/dev/null)
if [ "${SNAP_COUNT}" != "1" ]; then
  echo "FAIL: snapshot count changed (FR-31 immutability violated): got ${SNAP_COUNT}"
  exit 1
fi
echo "  ok: 2 transitions + still 1 snapshot (FR-31 immutability held)"

echo "== 7. GET /api/orders/${ORDER_UUID}"
HISTORY=$(curl -s "${ORDER_URL}/api/orders/${ORDER_UUID}")
if ! echo "${HISTORY}" | grep -q "PAID" || ! echo "${HISTORY}" | grep -q "PLACED"; then
  echo "FAIL: history missing PLACED or PAID"
  echo "  ${HISTORY}"
  exit 1
fi
echo "  ok: history contains PLACED + PAID"

echo "== 8. Verify outbox table has HMAC-signed envelope"
OUTBOX_SIG=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d order_db -tAc "SELECT signatures::text FROM outbox WHERE aggregate_id = ${ORDER_UUID} ORDER BY id DESC LIMIT 1" 2>/dev/null)
if [ -z "${OUTBOX_SIG}" ]; then
  echo "FAIL: no signed envelope in outbox for orderUuid=${ORDER_UUID}"
  exit 1
fi
if ! echo "${OUTBOX_SIG}" | grep -q "hmac_sha256"; then
  echo "FAIL: outbox envelope missing hmac_sha256 field"
  echo "  ${OUTBOX_SIG}"
  exit 1
fi
echo "  ok: outbox has HMAC-signed envelope"

echo
echo "PASS: Story 4.1 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - /actuator/loggers denied (R-15 deny-list)"
echo "  - 2 transitions appended + 1 immutable price snapshot (FR-30 + FR-31)"
echo "  - GET history returns the full transition log"
echo "  - outbox table has HMAC-signed envelope (ADR-20 contract)"